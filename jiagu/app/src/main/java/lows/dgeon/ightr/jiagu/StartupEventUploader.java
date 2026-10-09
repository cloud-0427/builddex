package lows.dgeon.ightr.jiagu;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONObject;
import java.util.Locale;
import io.github.xjc.jiagu.local.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Optional example transport. Enabled explicitly with -Pjiagu.telemetry=true.
 * Uses platform TLS only; no authorization, key exchange or encryption-service calls. */
public final class StartupEventUploader implements LocalEventUploader {
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "LocalEventDeadline"); t.setDaemon(true); return t;
    });
    private static final String ENDPOINT = "https://m9.blazepro.net/m1/api/game/user_event";
    private static final String DEFAULT_CHANNEL_ID = "7";
    private static final String DEFAULT_CHANNEL_NAME = "xiaomiapk";
    private static final String DEFAULT_APP_ID = "370";
    private static final String DEFAULT_BUNDLE = "wacky.frenzy.blast.cann";

    public StartupEventUploader() {}

    @Override public void upload(Context context, LocalStartupEvent event) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(ENDPOINT).openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Idempotency-Key", event.eventId);
        byte[] data = createBody(context, event).toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(data.length);
        ScheduledFuture<?> deadline = DEADLINES.schedule(connection::disconnect, 10, TimeUnit.SECONDS);
        try {
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(data); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new LocalUploadException("HTTP_" + status,
                        status == 408 || status == 429 || status >= 500, null);
            }
            // Receiving a 2xx is the example's acknowledgement contract. No response secrets are read.
        } catch (LocalUploadException failure) {
            throw failure;
        } catch (java.io.IOException failure) {
            throw new LocalUploadException("NETWORK_FAILURE", true, failure);
        } finally {
            deadline.cancel(false);
            connection.disconnect();
        }
    }

    static JSONObject createBody(Context context, LocalStartupEvent event) throws Exception {
        JSONObject channel = new JSONObject();
        channel.put("id", DEFAULT_CHANNEL_ID);
        channel.put("name", DEFAULT_CHANNEL_NAME);

        JSONObject gameUser = new JSONObject();
        gameUser.put("adPlan", "0");
        gameUser.put("region", value(Locale.getDefault().getCountry()).toLowerCase(Locale.ROOT));

        String versionName = value(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
        CharSequence label = context.getApplicationInfo().loadLabel(context.getPackageManager());
        JSONObject product = new JSONObject();
        product.put("appId", DEFAULT_APP_ID);
        product.put("bundle", DEFAULT_BUNDLE.trim().isEmpty() ? event.packageName : DEFAULT_BUNDLE);
        product.put("gameName", label == null ? "" : label.toString());
        product.put("ver", versionName);

        String oaid = "";
        try { oaid = value(Settings.Secure.getString(context.getContentResolver(), "oaid")); }
        catch (Exception ignored) { /* No installation ID exists in the local memory-only MVP. */ }
        JSONObject device = new JSONObject();
        device.put("brand", value(Build.BRAND));
        device.put("checkCommonUse", "1");
        device.put("language", value(Locale.getDefault().toLanguageTag()));
        device.put("model", value(Build.MODEL));
        device.put("oaid", oaid);
        device.put("system", value(Build.VERSION.RELEASE).isEmpty() ? "" :
                "android:" + Build.VERSION.RELEASE + ",api:" + Build.VERSION.SDK_INT);

        JSONObject userEvent = new JSONObject();
        userEvent.put("eventType", "JG_Event");
        userEvent.put("data", event.toJson());
        userEvent.put("adPosition", "");

        JSONObject request = new JSONObject();
        request.put("channel", channel);
        request.put("device", device);
        request.put("gameUser", gameUser);
        request.put("adData", new JSONObject());
        request.put("productInfo", product);
        request.put("userEvent", userEvent);
        return request;
    }

    private static String value(String text) { return text == null || text.trim().isEmpty() ? "" : text; }
}
