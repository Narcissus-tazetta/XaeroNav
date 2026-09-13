"""Regression checks for preserving impassable terrain in navigation fixtures."""
import gzip
import struct
import unittest
import zlib

from dump_terrain_columns import (
    classify, read_chunk, section_blocks, section_overlaps_band, WALL, SOFT,
)


class TerrainClassificationTest(unittest.TestCase):
    def test_unbreakable_blocks_remain_unbreakable(self):
        for name in ('minecraft:bedrock', 'minecraft:barrier'):
            self.assertEqual((WALL, False), classify(name))

    def test_netherrack_and_air_keep_their_distinct_movement_costs(self):
        self.assertEqual((SOFT, False), classify('minecraft:netherrack'))
        self.assertEqual((None, False), classify('minecraft:air'))


class SectionOverlapsBandTest(unittest.TestCase):
    """1.18以降のワールドはセクションYが負にもなる（岩盤の下）。"""

    def test_negative_section_below_band_is_excluded(self):
        # セクションY=-5 -> ブロックY -80..-65。バンドが0..255なら重ならない
        self.assertFalse(section_overlaps_band(-5, 0, 255))

    def test_negative_section_overlapping_band_is_included(self):
        # セクションY=-1 -> ブロックY -16..-1。バンドが-8..8と重なる
        self.assertTrue(section_overlaps_band(-1, -8, 8))

    def test_section_exactly_touching_band_edge_is_included(self):
        # セクションY=0 -> ブロックY 0..15。バンド上限が0ちょうどでも1マスだけ重なる
        self.assertTrue(section_overlaps_band(0, -64, 0))

    def test_section_just_outside_band_is_excluded(self):
        self.assertFalse(section_overlaps_band(1, -64, 15))


# --- 以下はNBT/regionフィクスチャの組み立て用ヘルパー（本体には無い、テスト専用のエンコーダ） ---

def _string_value(s):
    b = s.encode('utf-8')
    return 8, struct.pack('>H', len(b)) + b


def _byte_value(v):
    return 1, struct.pack('>b', v)


def _long_array_value(values):
    body = struct.pack('>i', len(values))
    for v in values:
        body += struct.pack('>Q', v & 0xFFFFFFFFFFFFFFFF)
    return 12, body


def _compound_value(fields):
    out = bytearray()
    for name, (tag_id, raw) in fields.items():
        out.append(tag_id)
        name_b = name.encode('utf-8')
        out += struct.pack('>H', len(name_b))
        out += name_b
        out += raw
    out.append(0)
    return 10, bytes(out)


def _list_value(tag_id, item_raw_values):
    out = bytes([tag_id]) + struct.pack('>i', len(item_raw_values))
    out += b''.join(item_raw_values)
    return 9, out


def _encode_root(fields):
    """`Nbt.root()`が読める形の、名前無しroot compoundを1つ作る。"""
    _, payload = _compound_value(fields)
    return bytes([10]) + struct.pack('>H', 0) + payload


def _pack_palette_indices(indices, bits):
    """`section_blocks`のno-cross-boundary展開と対になる詰め方（1.16以降の形式）。"""
    per_long = 64 // bits
    mask = (1 << bits) - 1
    longs = []
    for start in range(0, len(indices), per_long):
        chunk = indices[start:start + per_long]
        value = 0
        for slot, idx in enumerate(chunk):
            value |= (idx & mask) << (slot * bits)
        longs.append(value)
    return longs


def _section(y, palette_names, indices=None):
    """`section`辞書と同じ形のcompound raw値を作る。indices省略時は単色（palette1件）扱い。"""
    palette_items = [_compound_value({'Name': _string_value(n)}) for n in palette_names]
    block_states = {'palette': _list_value(10, [raw for _, raw in palette_items])}
    if indices is not None:
        bits = max(4, (len(palette_names) - 1).bit_length())
        block_states['data'] = _long_array_value(_pack_palette_indices(indices, bits))
    return _compound_value({
        'Y': _byte_value(y),
        'block_states': _compound_value(block_states),
    })


def _chunk_nbt(sections, status='minecraft:full'):
    _, sections_raw = _list_value(10, [raw for _, raw in sections])
    return _encode_root({
        'Status': _string_value(status),
        'sections': (9, sections_raw),
    })


def _region_with_single_chunk(cx, cz, compression, payload):
    """`read_chunk`が読める最小のregionバイト列。実際の.mcaと違いtimestamp表は省く
    （read_chunkはそれを読まない——このreader自身の契約をテストする）。"""
    body = struct.pack('>I', len(payload) + 1) + bytes([compression]) + payload
    # 4096バイト境界に詰める（read_chunkはパディングを読まないが、実ファイルの形に近づける）
    if len(body) % 4096:
        body += b'\x00' * (4096 - len(body) % 4096)
    header = bytearray(4096)
    header_index = 4 * ((cx & 31) + (cz & 31) * 32)
    sector_offset = 1  # header自身が1セクター(4096バイト)ぶん
    sector_count = len(body) // 4096
    struct.pack_into('>I', header, header_index, (sector_offset << 8) | sector_count)
    return bytes(header) + body


class ReadChunkGoldenTest(unittest.TestCase):
    """region/NBTの最小フィクスチャを自前で組み立てて、read_chunk/section_blocksの
    往復が壊れていないことを固定する（TOOL-01）。"""

    def test_uniform_section_round_trips_through_zlib(self):
        nbt = _chunk_nbt([_section(0, ['minecraft:stone'])])
        region = _region_with_single_chunk(0, 0, 2, zlib.compress(nbt))
        chunk = read_chunk(region, 0, 0)
        self.assertEqual('minecraft:full', chunk['Status'])
        blocks = section_blocks(chunk['sections'][0])
        self.assertEqual('minecraft:stone', blocks)

    def test_uniform_section_round_trips_through_gzip(self):
        nbt = _chunk_nbt([_section(0, ['minecraft:stone'])])
        region = _region_with_single_chunk(0, 0, 1, gzip.compress(nbt))
        chunk = read_chunk(region, 0, 0)
        blocks = section_blocks(chunk['sections'][0])
        self.assertEqual('minecraft:stone', blocks)

    def test_packed_palette_unpacks_in_yzx_order(self):
        palette = ['minecraft:air', 'minecraft:stone', 'minecraft:water']
        # x=0..15,z=0,y=0 だけ石、それ以外は空気。水は使わないが3色にしてbit幅を2にする
        indices = [0] * 4096
        for x in range(16):
            indices[x] = 1  # y*256 + z*16 + x, y=z=0なのでx＝そのままの添字
        nbt = _chunk_nbt([_section(0, palette, indices)])
        region = _region_with_single_chunk(0, 0, 2, zlib.compress(nbt))
        chunk = read_chunk(region, 0, 0)
        blocks = section_blocks(chunk['sections'][0])
        self.assertEqual(4096, len(blocks))
        self.assertEqual(['minecraft:stone'] * 16, blocks[0:16])
        self.assertEqual('minecraft:air', blocks[16])
        self.assertEqual('minecraft:air', blocks[256])  # z=1行目は空気のまま

    def test_missing_chunk_returns_none(self):
        region = bytes(4096 + 4096)  # header全ゼロ＝どのチャンクも未生成
        self.assertIsNone(read_chunk(region, 0, 0))

    def test_unsupported_compression_type_raises_instead_of_silently_misreading(self):
        nbt = _chunk_nbt([_section(0, ['minecraft:stone'])])
        # 3=非圧縮、4=LZ4のつもりで生のNBTをそのまま置く。対応外なので明示的に落ちてほしい
        region = _region_with_single_chunk(0, 0, 3, nbt)
        with self.assertRaises(ValueError):
            read_chunk(region, 0, 0)

    def test_truncated_compressed_payload_raises(self):
        nbt = _chunk_nbt([_section(0, ['minecraft:stone'])])
        compressed = zlib.compress(nbt)
        region = _region_with_single_chunk(0, 0, 2, compressed[:len(compressed) // 2])
        with self.assertRaises(zlib.error):
            read_chunk(region, 0, 0)


if __name__ == '__main__':
    unittest.main()
