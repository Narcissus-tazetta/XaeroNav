package net.prason.xaeronav.rail;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * 1つのワールドの1つの次元で見たレールを、チャンクごとに覚えてディスクへ書く。
 *
 * <p>ファイルはリージョン（32×32チャンク）ごとに1つ。作るときに次元のぶんを全部読む——1万本でも約40KBなので、
 * 必要になったリージョンだけ読む仕組みを持つほうが高くつく。
 *
 * <p>単一スレッドから使うこと（同期していない）。
 */
public final class RailStore {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final int MAGIC = 0x584E524C; // "XNRL"
    private static final int VERSION = 1;
    private static final String SUFFIX = ".rails";
    private static final Pattern FILE_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.rails");

    private final Path dir;
    private final Long2ObjectMap<int[]> chunks = new Long2ObjectOpenHashMap<>();
    private final LongSet dirtyRegions = new LongOpenHashSet();
    private int railCount;
    private long changes;

    private RailStore(Path dir) {
        this.dir = dir;
    }

    /** {@code dir}にある保存データを読む。ディレクトリがまだ無ければ空で始める（書くときに作る）。 */
    public static RailStore open(Path dir) throws IOException {
        RailStore store = new RailStore(dir);
        if (!Files.isDirectory(dir)) {
            return store;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "r.*" + SUFFIX)) {
            for (Path file : files) {
                Matcher name = FILE_NAME.matcher(file.getFileName().toString());
                if (name.matches()) {
                    store.readRegion(file, Integer.parseInt(name.group(1)), Integer.parseInt(name.group(2)));
                }
            }
        }
        return store;
    }

    /**
     * 壊れた・知らない版のファイルは、そのリージョンを空として扱い、ログに残して読み飛ばす。
     * 同じリージョンを次に見たときに書き直されるので、ゲームを止めるほどのことではない。
     */
    private void readRegion(Path file, int regionX, int regionZ) throws IOException {
        Long2ObjectMap<int[]> read = new Long2ObjectOpenHashMap<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version != VERSION) {
                LOGGER.warn("XaeroNav: ignoring rail data with unknown format (magic={}, version={}): {}",
                        Integer.toHexString(magic), version, file);
                return;
            }
            int chunkCount = in.readInt();
            for (int i = 0; i < chunkCount; i++) {
                int chunkX = in.readInt();
                int chunkZ = in.readInt();
                int count = in.readInt();
                if (chunkX >> 5 != regionX || chunkZ >> 5 != regionZ || count <= 0 || count > 16 * 16 * 4096) {
                    LOGGER.warn("XaeroNav: ignoring corrupted rail data (chunk {},{} with {} rails): {}",
                            chunkX, chunkZ, count, file);
                    return;
                }
                int[] rails = new int[count];
                for (int j = 0; j < count; j++) {
                    rails[j] = in.readInt();
                    if (!RailCell.valid(rails[j])) {
                        LOGGER.warn("XaeroNav: ignoring corrupted rail data (bad rail value): {}", file);
                        return;
                    }
                }
                read.put(chunkKey(chunkX, chunkZ), rails);
            }
        } catch (EOFException e) {
            LOGGER.warn("XaeroNav: ignoring truncated rail data: {}", file);
            return;
        }
        for (Long2ObjectMap.Entry<int[]> entry : read.long2ObjectEntrySet()) {
            chunks.put(entry.getLongKey(), entry.getValue());
            railCount += entry.getValue().length;
        }
    }

    /**
     * チャンクを見た結果で置き換える。{@code rails}が空ならそのチャンクを忘れる。
     * 中身が前と同じなら書き出しの対象にしない。
     */
    public void putChunk(int chunkX, int chunkZ, int[] rails) {
        long key = chunkKey(chunkX, chunkZ);
        int[] previous = rails.length == 0 ? chunks.remove(key) : chunks.put(key, rails);
        if (previous == null ? rails.length == 0 : Arrays.equals(previous, rails)) {
            return;
        }
        railCount += rails.length - (previous == null ? 0 : previous.length);
        dirtyRegions.add(chunkKey(chunkX >> 5, chunkZ >> 5));
        changes++;
    }

    /** 中身が変わった回数。開いた後は0から数える。 */
    public long changes() {
        return changes;
    }

    /** 覚えている全チャンク。書き換えないこと。 */
    public Long2ObjectMap<int[]> chunks() {
        return Long2ObjectMaps.unmodifiable(chunks);
    }

    /** 覚えているチャンクのレール。無ければ{@code null}。 */
    public int @Nullable [] chunk(int chunkX, int chunkZ) {
        return chunks.get(chunkKey(chunkX, chunkZ));
    }

    public int railCount() {
        return railCount;
    }

    public int chunkCount() {
        return chunks.size();
    }

    public boolean dirty() {
        return !dirtyRegions.isEmpty();
    }

    /** 変わったリージョンを書き出す。空になったリージョンはファイルごと消す。 */
    public void flush() throws IOException {
        if (dirtyRegions.isEmpty()) {
            return;
        }
        Files.createDirectories(dir);
        for (long region : dirtyRegions.toLongArray()) {
            writeRegion((int) (region >> 32), (int) region);
            dirtyRegions.remove(region);
        }
    }

    private void writeRegion(int regionX, int regionZ) throws IOException {
        Path file = dir.resolve("r." + regionX + "." + regionZ + SUFFIX);
        Long2ObjectMap<int[]> inRegion = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<int[]> entry : chunks.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            if ((int) (key >> 32) >> 5 == regionX && (int) key >> 5 == regionZ) {
                inRegion.put(key, entry.getValue());
            }
        }
        if (inRegion.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        // 書きかけで落ちても前のファイルが残るよう、隣に書いてから置き換える
        Path temp = dir.resolve(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(inRegion.size());
            for (Long2ObjectMap.Entry<int[]> entry : inRegion.long2ObjectEntrySet()) {
                long key = entry.getLongKey();
                out.writeInt((int) (key >> 32));
                out.writeInt((int) key);
                out.writeInt(entry.getValue().length);
                for (int rail : entry.getValue()) {
                    out.writeInt(rail);
                }
            }
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
    }
}
