#!/usr/bin/env python3
"""Minecraftのワールド保存データ(.mca)から、テスト用の地形フィクスチャを書き出す。

出力は `TerrainFixture`(src/test/java/.../world/TerrainFixture.java) が読む形式:

    minX minY minZ maxX maxY maxZ
    x z <種別><fromY>,<toY> <種別><fromY>,<toY> …

種別は `FakeCells` の記号1文字。省略（数字で始まる）なら石として読む——
種別を持たなかった頃に書き出したフィクスチャをそのまま読めるようにするため。

使い方:
    python3 tools/dump_terrain_columns.py <region-dir> <minX> <minZ> <maxX> <maxZ> \
        --band <下>,<上> --out src/test/resources/<name>.txt.gz
"""
import argparse
import gzip
import math
import os
import struct
import zlib

STONE, SOFT, WATER, LAVA, VINE, LADDER, WALL = '#', 'D', '~', 'L', 'V', 'H', 'B'

# 通り抜けられる（空気として書き出す）ブロック。草・花・松明の類までを含める——
# 固体として書くと、地表が一面「掘らないと進めない」地形になる
PASSABLE_SUFFIXES = (
    '_air', 'air', 'grass', 'fern', 'flower', 'tulip', 'orchid', 'bluet', 'daisy', 'rose',
    'poppy', 'dandelion', 'cornflower', 'lily_of_the_valley', 'allium', 'sapling', 'torch',
    'sign', 'button', 'lever', 'rail', 'pressure_plate', 'snow', 'carpet', 'mushroom',
    'seagrass', 'kelp', 'kelp_plant', 'sugar_cane', 'dead_bush', 'sweet_berry_bush',
    'cobweb', 'sculk_vein', 'glow_lichen', 'nether_sprouts', 'wither_rose', 'fire',
    'soul_fire', 'vine_end',
)
PASSABLE_EXACT = {
    'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air', 'minecraft:short_grass',
    'minecraft:tall_grass', 'minecraft:large_fern', 'minecraft:snow', 'minecraft:light',
    'minecraft:structure_void', 'minecraft:moving_piston', 'minecraft:tripwire',
    'minecraft:nether_portal', 'minecraft:end_portal', 'minecraft:end_gateway',
}
# 値は (記号, 地面の高さの基準にしないか)
CLIMBABLE = {'minecraft:ladder': (LADDER, False), 'minecraft:vine': (VINE, True),
             'minecraft:weeping_vines': (VINE, True), 'minecraft:weeping_vines_plant': (VINE, True),
             'minecraft:twisting_vines': (VINE, True), 'minecraft:twisting_vines_plant': (VINE, True),
             'minecraft:cave_vines': (VINE, True), 'minecraft:cave_vines_plant': (VINE, True),
             'minecraft:scaffolding': (LADDER, False)}

# 掘れない壁として書き出す。実機の`DiggableBlocks`は許可リスト制で、原木・幹は許可していない
LOG_SUFFIXES = ('_log', '_wood', '_stem', '_hyphae')
# `_stem`で終わるが実機では掘れる
NOT_LOG = {'minecraft:mushroom_stem'}
SOFT_BLOCKS = {
    'minecraft:dirt', 'minecraft:grass_block', 'minecraft:coarse_dirt', 'minecraft:rooted_dirt',
    'minecraft:podzol', 'minecraft:mycelium', 'minecraft:sand', 'minecraft:red_sand',
    'minecraft:gravel', 'minecraft:clay', 'minecraft:soul_sand', 'minecraft:soul_soil',
    'minecraft:snow_block', 'minecraft:mud', 'minecraft:farmland', 'minecraft:dirt_path',
    'minecraft:moss_block', 'minecraft:sculk', 'minecraft:netherrack', 'minecraft:crimson_nylium',
    'minecraft:warped_nylium', 'minecraft:soul_sand',
    'minecraft:mangrove_roots', 'minecraft:muddy_mangrove_roots',
}


def classify(name):
    """(記号, 地面の高さの基準にしないか) を返す。記号がNoneなら空気として書き出さない。

    第2の値は`--depth`がどこから深さを測るかにだけ効く。木・ツタ・竹は地面の上に生えている
    ものなので基準にできず、水と溶岩は液面ではなくその下の地形から測らないと海底が削れる。
    """
    if name in PASSABLE_EXACT:
        return None, False
    short = name.split(':', 1)[1] if ':' in name else name
    if short.endswith(PASSABLE_SUFFIXES) and 'block' not in short:
        return None, False
    if name in CLIMBABLE:
        return CLIMBABLE[name]
    if name == 'minecraft:water' or short.endswith('_water'):
        return WATER, True
    if name == 'minecraft:lava':
        return LAVA, True
    # 葉・竹は実機では「掘って通る固体」。空気として捨てるとジャングルの天蓋が地形から消える
    if short.endswith('leaves'):
        return SOFT, True
    if name == 'minecraft:bamboo':
        return SOFT, True
    if name not in NOT_LOG and short.endswith(LOG_SUFFIXES):
        return WALL, True
    if name in SOFT_BLOCKS:
        return SOFT, False
    return STONE, False


class Nbt:
    """必要なタグだけを読む最小のNBTリーダー。"""

    def __init__(self, data):
        self.d = data
        self.i = 0

    def u1(self):
        v = self.d[self.i]
        self.i += 1
        return v

    def raw(self, fmt, size):
        v = struct.unpack_from(fmt, self.d, self.i)[0]
        self.i += size
        return v

    def name(self):
        length = self.raw('>H', 2)
        s = self.d[self.i:self.i + length].decode('utf-8', 'replace')
        self.i += length
        return s

    def value(self, tag):
        if tag == 1:
            return self.raw('>b', 1)
        if tag == 2:
            return self.raw('>h', 2)
        if tag == 3:
            return self.raw('>i', 4)
        if tag == 4:
            return self.raw('>q', 8)
        if tag == 5:
            return self.raw('>f', 4)
        if tag == 6:
            return self.raw('>d', 8)
        if tag == 7:
            n = self.raw('>i', 4)
            v = self.d[self.i:self.i + n]
            self.i += n
            return v
        if tag == 8:
            return self.name()
        if tag == 9:
            item = self.u1()
            n = self.raw('>i', 4)
            return [self.value(item) for _ in range(n)]
        if tag == 10:
            out = {}
            while True:
                child = self.u1()
                if child == 0:
                    return out
                # 名前を先に読む。`out[self.name()] = self.value(child)` と書くと、Pythonは
                # 右辺を先に評価するので値と名前が入れ替わる
                key = self.name()
                out[key] = self.value(child)
        if tag == 11:
            n = self.raw('>i', 4)
            v = struct.unpack_from('>%di' % n, self.d, self.i)
            self.i += 4 * n
            return list(v)
        if tag == 12:
            n = self.raw('>i', 4)
            v = struct.unpack_from('>%dq' % n, self.d, self.i)
            self.i += 8 * n
            return list(v)
        raise ValueError('unknown tag %d' % tag)

    def root(self):
        tag = self.u1()
        self.name()
        return self.value(tag)


def read_chunk(region, cx, cz):
    header_index = 4 * ((cx & 31) + (cz & 31) * 32)
    entry = struct.unpack_from('>I', region, header_index)[0]
    if entry == 0:
        return None
    offset = (entry >> 8) * 4096
    length = struct.unpack_from('>I', region, offset)[0]
    compression = region[offset + 4]
    payload = region[offset + 5:offset + 4 + length]
    if compression == 1:
        payload = gzip.decompress(payload)
    elif compression == 2:
        payload = zlib.decompress(payload)
    return Nbt(payload).root()


def section_blocks(section):
    """1セクション(16^3)のブロック名を、y*256+z*16+x の並びで返す。全部同じなら文字列1つ。"""
    states = section.get('block_states')
    if states is None:
        return None
    palette = [entry['Name'] for entry in states['palette']]
    data = states.get('data')
    if not data or len(palette) == 1:
        return palette[0]
    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    out = []
    for packed in data:
        packed &= 0xFFFFFFFFFFFFFFFF
        for slot in range(per_long):
            if len(out) == 4096:
                break
            out.append(palette[(packed >> (slot * bits)) & mask])
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('region_dir')
    parser.add_argument('min_x', type=int)
    parser.add_argument('min_z', type=int)
    parser.add_argument('max_x', type=int)
    parser.add_argument('max_z', type=int)
    parser.add_argument('--band', required=True, help='書き出すYの範囲 "下,上"')
    parser.add_argument('--depth', type=int, default=0,
                        help='列ごとに、地面のいちばん上からこの深さまでだけ書き出す（0で無制限）。'
                             '地表を歩く経路しか見ないなら、下の岩盤まで書いてもファイルが太るだけ。'
                             '木・ツタ・竹は基準にも含めず、常にそのまま残す')
    parser.add_argument('--out', required=True)
    args = parser.parse_args()

    band_low, band_high = (int(v) for v in args.band.split(','))
    columns = {}
    for region_x in range(args.min_x >> 9, (args.max_x >> 9) + 1):
        for region_z in range(args.min_z >> 9, (args.max_z >> 9) + 1):
            path = os.path.join(args.region_dir, 'r.%d.%d.mca' % (region_x, region_z))
            if not os.path.exists(path):
                continue
            region = open(path, 'rb').read()
            for cx in range(region_x * 32, region_x * 32 + 32):
                for cz in range(region_z * 32, region_z * 32 + 32):
                    if cx * 16 > args.max_x or cx * 16 + 15 < args.min_x:
                        continue
                    if cz * 16 > args.max_z or cz * 16 + 15 < args.min_z:
                        continue
                    chunk = read_chunk(region, cx, cz)
                    if chunk is None or chunk.get('Status') not in (
                            'minecraft:full', 'full'):
                        continue
                    for section in chunk.get('sections', []):
                        base_y = section['Y'] * 16
                        if base_y > band_high or base_y + 15 < band_low:
                            continue
                        blocks = section_blocks(section)
                        if blocks is None:
                            continue
                        uniform = classify(blocks) if isinstance(blocks, str) else None
                        if uniform is not None and uniform[0] is None:
                            continue
                        for local_y in range(16):
                            y = base_y + local_y
                            if y < band_low or y > band_high:
                                continue
                            for local_z in range(16):
                                z = cz * 16 + local_z
                                if z < args.min_z or z > args.max_z:
                                    continue
                                for local_x in range(16):
                                    x = cx * 16 + local_x
                                    if x < args.min_x or x > args.max_x:
                                        continue
                                    if uniform is not None:
                                        kind, not_ground = uniform
                                    else:
                                        kind, not_ground = classify(
                                            blocks[local_y * 256 + local_z * 16 + local_x])
                                    if kind is None:
                                        continue
                                    columns.setdefault((x, z), []).append((y, kind, not_ground))

    lines = []
    min_y, max_y = band_high, band_low
    for (x, z), cells in sorted(columns.items()):
        cells.sort()
        if args.depth > 0:
            # 基準は地面のいちばん上。列の最上端から測ると木やツタの下の地面ごと削れる——
            # 葉を固体として書くジャングルでは、それだと1割の列から地面が消える
            ground = next((y for y, _, not_ground in reversed(cells) if not not_ground),
                          cells[-1][0])
            cells = [c for c in cells if c[0] >= ground - args.depth]
        runs = []
        run_from, run_to, run_kind = cells[0][0], cells[0][0], cells[0][1]
        for y, kind, _ in cells[1:]:
            if y == run_to + 1 and kind == run_kind:
                run_to = y
                continue
            runs.append((run_kind, run_from, run_to))
            run_from, run_to, run_kind = y, y, kind
        runs.append((run_kind, run_from, run_to))
        min_y = min(min_y, runs[0][1])
        max_y = max(max_y, runs[-1][2])
        lines.append('%d %d %s' % (x, z, ' '.join(
            '%s%d,%d' % (kind, low, high) for kind, low, high in runs)))

    header = '%d %d %d %d %d %d' % (args.min_x - 16, min_y - 8, args.min_z - 16,
                                    args.max_x + 16, max_y + 24, args.max_z + 16)
    with gzip.open(args.out, 'wt', encoding='utf-8') as out:
        out.write(header + '\n')
        out.write('\n'.join(lines) + '\n')
    print('%s 列=%d Y=%d..%d %.1fKB' % (args.out, len(columns), min_y, max_y,
                                        os.path.getsize(args.out) / 1024.0))


if __name__ == '__main__':
    main()
