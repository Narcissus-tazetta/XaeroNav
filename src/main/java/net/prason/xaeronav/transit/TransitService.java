package net.prason.xaeronav.transit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.prason.xaeronav.XaeroNav;
import org.jspecify.annotations.Nullable;

/**
 * MTRの地図サーバーへ乗り換え案内を問い合わせる。
 *
 * <p>MTRはワールドに入るとクライアント側にHTTPサーバーを立てる（{@link MtrLink#port}）。シングルでは
 * シミュレーターの地図サーバーそのもの、マルチでは同じ要求をパケットでサーバーへ中継する口で、どちらも同じ答えを返す。
 * MTRのパケットを直接送らないのは、版ごとにクラスが違うため。
 *
 * <p>答えはシミュレーターの次のtickで作られるので1〜3秒かかる。専用のスレッド1本で順に投げ、結果は呼び出し側が
 * 渡した受け口へそのスレッドから返す。
 */
public final class TransitService {

    /** サーバー側の地図サーバーが無効だと、中継は返事をしないまま切れない。これで見切る。 */
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    /** 駅・路線の一覧を使い回す時間。MTR側も30秒キャッシュしている。 */
    private static final long NETWORK_TTL_MILLIS = 60_000L;

    /**
     * 問い合わせの結果。{@code itinerary}があれば電車に乗る答え。{@code failure}があれば返事を読めなかった
     * （原因の英語の説明）。どちらも無ければ、返事は来たが電車に乗る区間が無い。
     */
    public record Outcome(@Nullable Itinerary itinerary, @Nullable String failure) {
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XaeroNav-transit");
        thread.setDaemon(true);
        return thread;
    });

    // 以下はworkerスレッドだけが触る
    private @Nullable TransitNetwork network;
    private int networkPort;
    private @Nullable String networkWorld;
    private int networkDimension;
    private long networkFetchedMillis;

    /**
     * @param worldId {@code minecraft/overworld}の形（MTRの次元の書き方）
     * @param result  workerスレッドから呼ばれる
     */
    public void ask(int port, String worldId, BlockPos from, BlockPos to, Consumer<Outcome> result) {
        worker.execute(() -> {
            Outcome outcome;
            try {
                outcome = new Outcome(directions(port, worldId, from, to), null);
            } catch (IOException | JsonParseException | TransitFormatException e) {
                XaeroNav.LOGGER.warn("XaeroNav: MTR directions request failed: {}", e.toString());
                outcome = new Outcome(null, e.toString());
            }
            result.accept(outcome);
        });
    }

    private @Nullable Itinerary directions(int port, String worldId, BlockPos from, BlockPos to) throws IOException {
        boolean cached = network != null && networkPort == port && worldId.equals(networkWorld)
                && System.currentTimeMillis() - networkFetchedMillis < NETWORK_TTL_MILLIS;
        if (!cached) {
            fetchNetwork(port, worldId);
        }
        JsonObject body = new JsonObject();
        body.addProperty("startPositionX", from.getX());
        body.addProperty("startPositionY", from.getY());
        body.addProperty("startPositionZ", from.getZ());
        body.addProperty("endPositionX", to.getX());
        body.addProperty("endPositionY", to.getY());
        body.addProperty("endPositionZ", to.getZ());
        body.addProperty("startTime", System.currentTimeMillis());
        JsonObject response = post(port, "directions", networkDimension, body.toString());
        try {
            return Itinerary.parse(response, network);
        } catch (TransitFormatException stale) {
            if (!cached) {
                throw stale;
            }
            // 使い回した一覧に無い路線・ホームかもしれない（一覧を取った後に路線が変わった）
            fetchNetwork(port, worldId);
            return Itinerary.parse(response, network);
        }
    }

    private void fetchNetwork(int port, String worldId) throws IOException {
        network = null;
        // 次元の並びはどの次元に聞いても同じものが返る。番号が分かるまでは0番に聞く
        TransitNetwork first = TransitNetwork.parse(data(post(port, "stations-and-routes", 0, "{}")));
        int dimension = first.dimensionIndex(worldId);
        if (dimension < 0) {
            throw new TransitFormatException("MTR does not serve dimension " + worldId);
        }
        network = dimension == 0 ? first
                : TransitNetwork.parse(data(post(port, "stations-and-routes", dimension, "{}")));
        networkPort = port;
        networkWorld = worldId;
        networkDimension = dimension;
        networkFetchedMillis = System.currentTimeMillis();
    }

    private static JsonObject data(JsonObject response) {
        JsonElement data = response.get("data");
        if (data == null || !data.isJsonObject()) {
            throw new TransitFormatException("no 'data' in the response");
        }
        return data.getAsJsonObject();
    }

    @SuppressWarnings("deprecation") // 1.16.5が同梱するGsonにはJsonParser.parseStringが無い
    private static JsonObject post(int port, String endpoint, int dimension, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "http://localhost:" + port + "/mtr/api/map/" + endpoint + "?dimension=" + dimension).toURL().openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status + " from MTR " + endpoint);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    bytes.write(buffer, 0, read);
                }
            }
            JsonElement parsed = new JsonParser().parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new TransitFormatException("MTR " + endpoint + " did not return an object");
            }
            return parsed.getAsJsonObject();
        } finally {
            connection.disconnect();
        }
    }
}
