package net.prason.xaeronav.client;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongPredicate;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelResource;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.rail.RailBlocks;
import net.prason.xaeronav.rail.RailStore;
import net.prason.xaeronav.util.DaemonThreads;
import net.prason.xaeronav.util.GameCompat;

/**
 * 読み込まれたチャンクのレールを拾い、ワールド・次元ごとに{@link RailStore}へ覚えさせる。
 *
 * <p>Xaeroの地図はレールを記録しない（地図色の無いブロックとして飛ばされる）ので、遠くの線路を知るには
 * 自分で見たものを覚えておくしかない。
 *
 * <p>メインスレッドは読み込み済みチャンクへの参照を集めるだけで、ブロックを読むのはワーカー
 * （{@code ChunkView}と同じ考え方。競合の扱いもそちらの説明のとおりで、読み違いは次の見直しで直る）。
 * {@link RailStore}に触るのはワーカーだけ。
 */
public final class RailMemory {

    public static final RailMemory INSTANCE = new RailMemory();

    /** 読み込み済みチャンクを見回す間隔（tick）。 */
    private static final int PASS_INTERVAL_TICKS = 20;
    /**
     * 同じチャンクを見直す間隔（tick）。読み込まれている間に置かれた・壊されたレールはこれで拾う。
     * レールの無い区画はパレットを見るだけで飛ばせるので、全チャンクを30秒ごとに見直しても軽い。
     */
    private static final int RESCAN_TICKS = 600;
    /** 1回に調べるチャンクの上限。ワールドへ入った直後に数百チャンクを一度に抱えないため。 */
    private static final int MAX_CHUNKS_PER_PASS = 64;
    /** 変わったリージョンを書き出す間隔（ミリ秒）。 */
    private static final long FLUSH_INTERVAL_MILLIS = 30_000L;

    private final ExecutorService worker = DaemonThreads.singleThread("xaeronav-rail-memory");
    /** 前の回の仕事がまだ終わっていなければ次を積まない。積み上がると見直しが現在から遅れていく。 */
    private final AtomicBoolean busy = new AtomicBoolean();

    // ここから下のメインスレッド側の状態は、メインスレッドだけが触る
    private final Long2ObjectMap<Seen> seen = new Long2ObjectOpenHashMap<>();
    private @Nullable String currentKey;
    private long tick;

    // ワーカー側の状態
    private @Nullable RailStore store;
    private String storeKey = "";
    private long lastFlushMillis;

    // /xaeronav debug 用にワーカーが書く
    private volatile String status = "not started";

    private RailMemory() {
    }

    private static final class Seen {
        final WeakReference<LevelChunk> chunk;
        final long scannedAt;

        Seen(LevelChunk chunk, long scannedAt) {
            this.chunk = new WeakReference<>(chunk);
            this.scannedAt = scannedAt;
        }
    }

    private record Job(int chunkX, int chunkZ, LevelChunk chunk) {
    }

    public void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return;
        }
        tick++;
        String key = worldKey(mc) + "/" + GameCompat.dimensionId(level.dimension());
        if (!key.equals(currentKey)) {
            currentKey = key;
            seen.clear();
            Path dir = storageDir(mc, key);
            worker.execute(() -> switchTo(dir, key));
        }
        if (tick % PASS_INTERVAL_TICKS != 0 || busy.get()) {
            return;
        }
        List<Job> jobs = collect(level, player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4,
                ClientCompat.renderDistance(mc.options) + 1);
        if (jobs.isEmpty()) {
            return;
        }
        int minSection = GameCompat.minSection(level);
        busy.set(true);
        worker.execute(() -> {
            try {
                scan(jobs, minSection);
            } finally {
                busy.set(false);
            }
        });
    }

    /** まだ見ていない・読み込み直された・見直しの時期が来たチャンクを、まだ見ていないものから選ぶ。 */
    private List<Job> collect(Level level, int centerX, int centerZ, int radius) {
        List<Job> fresh = new ArrayList<>();
        List<Job> stale = new ArrayList<>();
        for (int chunkX = centerX - radius; chunkX <= centerX + radius; chunkX++) {
            for (int chunkZ = centerZ - radius; chunkZ <= centerZ + radius; chunkZ++) {
                long chunkKey = seenKey(chunkX, chunkZ);
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    seen.remove(chunkKey);
                    continue;
                }
                Seen last = seen.get(chunkKey);
                if (last == null || last.chunk.get() != chunk) {
                    fresh.add(new Job(chunkX, chunkZ, chunk));
                } else if (tick - last.scannedAt >= RESCAN_TICKS) {
                    stale.add(new Job(chunkX, chunkZ, chunk));
                }
            }
        }
        // 窓の外へ出たチャンクの記録を捨てる。持ち続けると移動した距離に比例して膨らむ
        LongPredicate outside = chunkKey -> Math.abs((int) (chunkKey >> 32) - centerX) > radius
                || Math.abs((int) chunkKey - centerZ) > radius;
        seen.keySet().removeIf(outside);
        List<Job> jobs = new ArrayList<>(Math.min(MAX_CHUNKS_PER_PASS, fresh.size() + stale.size()));
        for (List<Job> group : List.of(fresh, stale)) {
            for (Job job : group) {
                if (jobs.size() == MAX_CHUNKS_PER_PASS) {
                    break;
                }
                jobs.add(job);
                seen.put(seenKey(job.chunkX(), job.chunkZ()), new Seen(job.chunk(), tick));
            }
        }
        return jobs;
    }

    private static long seenKey(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
    }

    /** 抜けるときに書き出して手放す。次に入ったワールドで同じ記録を使い回さないため。 */
    public void onLoggingOut() {
        currentKey = null;
        seen.clear();
        worker.execute(() -> switchTo(null, "not in a world"));
    }

    public String debugLine() {
        return "rail memory: " + status;
    }

    private void switchTo(@Nullable Path dir, String key) {
        flush();
        store = null;
        storeKey = key;
        if (dir == null) {
            updateStatus();
            return;
        }
        try {
            store = RailStore.open(dir);
            lastFlushMillis = System.currentTimeMillis();
            updateStatus();
        } catch (IOException e) {
            // 読めないなら覚えない。覚えるのは経路の質を上げるためだけなので、ゲームは続けられる
            XaeroNav.LOGGER.warn("XaeroNav: cannot read rail data in {}", dir, e);
            status = "disabled (cannot read " + dir + ": " + e + ")";
        }
    }

    private void scan(List<Job> jobs, int minSection) {
        RailStore current = store;
        if (current == null) {
            return;
        }
        for (Job job : jobs) {
            current.putChunk(job.chunkX(), job.chunkZ(), railsIn(job.chunk(), minSection));
        }
        if (System.currentTimeMillis() - lastFlushMillis >= FLUSH_INTERVAL_MILLIS) {
            flush();
        }
        updateStatus();
    }

    private void flush() {
        RailStore current = store;
        if (current == null || !current.dirty()) {
            return;
        }
        try {
            current.flush();
        } catch (IOException e) {
            // 次の書き出しでもう一度試す（変わったリージョンの印は残っている）
            XaeroNav.LOGGER.warn("XaeroNav: cannot write rail data", e);
        }
        lastFlushMillis = System.currentTimeMillis();
    }

    private void updateStatus() {
        RailStore current = store;
        status = current == null ? storeKey
                : current.railCount() + " rails in " + current.chunkCount() + " chunks for " + storeKey
                        + (current.dirty() ? " (unsaved changes)" : "");
    }

    static int[] railsIn(LevelChunk chunk, int minSection) {
        LevelChunkSection[] sections = chunk.getSections();
        IntArrayList rails = null;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            // 1.17以前は空のセクションをnullで持つ
            if (section == null || !section.maybeHas(RailBlocks::isRail)) {
                continue;
            }
            int baseY = (minSection + i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (RailBlocks.isRail(state)) {
                            if (rails == null) {
                                rails = new IntArrayList();
                            }
                            rails.add(RailBlocks.encode(state, x, baseY + y, z));
                        }
                    }
                }
            }
        }
        return rails == null ? new int[0] : rails.toIntArray();
    }

    /**
     * ワールドの見分け。1人用はセーブフォルダ名、マルチはサーバーのアドレス。
     *
     * <p>Xaeroの世界地図はXaeroNavの必須の前提ではないので、Xaeroの見分け方には頼らない。そのぶん、
     * 1つのサーバーの中で同じ次元名の別ワールドを行き来する構成（ロビーと複数のサバイバル等）は見分けられない。
     * 混ざっても、そのチャンクを次に見たときに見た中身で置き換わる。
     */
    private static String worldKey(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null) {
            Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            return "sp-" + root.getFileName();
        }
        ServerData remote = mc.getCurrentServer();
        return "mp-" + (remote == null ? "unknown" : remote.ip);
    }

    private static Path storageDir(Minecraft mc, String key) {
        String[] parts = key.split("/", 2);
        return mc.gameDirectory.toPath().resolve(XaeroNav.MOD_ID).resolve("rails")
                .resolve(safeName(parts[0])).resolve(safeName(parts[1]));
    }

    /** ファイル名に使えない文字を潰す。潰して同じ名前になる別の値と分けるため、元の値のハッシュを足す。 */
    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + Integer.toHexString(value.hashCode());
    }
}
