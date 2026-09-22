package lows.dgeon.ightr.jiagu;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;
import org.conscrypt.Conscrypt;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Provider;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import io.github.xjc.jiagu.JiaguStartupEvent;
import io.github.xjc.jiagu.JiaguStartupLogUploader;
import io.github.xjc.jiagu.JiaguStartupUploadException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Minimal shell-safe startup event reporter.
 *
 * <p>The path follows the aiKeMeiTrackEvent endpoint convention. No AKM SDK
 * initialization or retry queue is used.</p>
 */
public final class StartupEventUploader implements JiaguStartupLogUploader {
    private static final String TAG = "JG_Event";

    // Startup telemetry must not leave the single shell dispatcher blocked for 15 seconds
    // per event. OkHttp also keeps the fully-consumed response connection available for
    // the following startup stage, avoiding a fresh TLS handshake for every event.
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int IO_TIMEOUT_MS = 6_000;
    private static final int CALL_TIMEOUT_MS = 8_000;
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.get("application/json; charset=utf-8");

    private static final String EVENT_ENDPOINT =
            "https://m9.blazepro.net/m1/api/game/user_event";
    /**
     * 渠道id、appid、 bundle, 这3个必须要对的上
     */
    private static final String DEFAULT_CHANNEL_ID = "7";
    private static final String DEFAULT_CHANNEL_NAME = "xiaomiapk";
    private static final String DEFAULT_APP_ID = "370";
    private static final String DEFAULT_BUNDLE = "wacky.frenzy.blast.cann";

    public StartupEventUploader() {
    }

    private static final class HttpClientHolder {
        private static final OkHttpClient INSTANCE = createHttpClient();
    }

    @Override
    public void upload(Context appContext, JiaguStartupEvent event) {
        if (event == null || appContext == null) {
            return;
        }
        if (!event.isFirstLaunch() || !event.isMainProcess()) {
            // 只有第一次主程序启动，才打印日志，否则先忽略
            return;
        }

        try {
            byte[] body = createBody(appContext, event).toString()
                    .getBytes(StandardCharsets.UTF_8);
            Request request = new Request.Builder()
                    .url(EVENT_ENDPOINT)
                    .header("Accept", "application/json")
                    .header("Idempotency-Key", event.getTelemetryEventId())
                    .post(RequestBody.create(body, JSON_MEDIA_TYPE))
                    .build();
            // Response.close() is essential: it releases the body and allows OkHttp to
            // reuse its HTTPS socket for the next startup stage. The former
            // HttpsURLConnection implementation never consumed either response stream.
            try (Response response = HttpClientHolder.INSTANCE.newCall(request).execute()) {
                int status = response.code();
                if (status >= 200 && status < 300) {
                    Log.d(TAG, "Startup event: status=" + status + " " + event);
                    return;
                }
                boolean retryable = status == 401 || status == 403 || status == 404
                        || status == 408 || status == 425 || status == 429 || status >= 500;
                long retryAfterMillis = retryAfterMillis(response.header("Retry-After"));
                throw new JiaguStartupUploadException("HTTP_" + status, retryable,
                        retryAfterMillis, null);
            }
        } catch (JiaguStartupUploadException error) {
            throw error;
        } catch (Exception error) {
            throw new JiaguStartupUploadException("NETWORK_FAILURE", true, 0L, error);
        }
    }

    private static OkHttpClient createHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(IO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(IO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                // OkHttp retries safe recoverable connection failures, but its call
                // timeout still bounds the whole best-effort telemetry operation.
                .retryOnConnectionFailure(true);
        try {
            Provider provider = Conscrypt.newProviderBuilder()
                    .provideTrustManager(true)
                    .build();
            X509TrustManager trustManager = StartupTls.conscryptTrustManager(provider);
            SSLContext context = SSLContext.getInstance("TLS", provider);
            context.init(null, new TrustManager[]{trustManager}, null);
            builder.sslSocketFactory(context.getSocketFactory(), trustManager);
            Log.i(TAG, "Using bundled Conscrypt TLS: provider=" + provider.getName());
        } catch (Exception error) {
            // Keep reporting optional. A ROM/provider mismatch must not affect startup.
            Log.w(TAG, "Cannot initialize bundled Conscrypt TLS; using platform TLS", error);
        }
        return builder.build();
    }

    private static JSONObject createBody(Context context, JiaguStartupEvent event) throws Exception {
        JSONObject channel = new JSONObject();
        channel.put("id", DEFAULT_CHANNEL_ID);
        channel.put("name", DEFAULT_CHANNEL_NAME);

        JSONObject gameUser = new JSONObject();
        gameUser.put("adPlan", "0");
        gameUser.put("region", countryOrDefault(""));

        JSONObject productInfo = new JSONObject();
        productInfo.put("appId", DEFAULT_APP_ID);
        productInfo.put("bundle", isBlank(DEFAULT_BUNDLE) ?
                event.getPackageName() : DEFAULT_BUNDLE);
        productInfo.put("gameName", valueOrDefault(
                applicationLabel(context), ""));
        productInfo.put("ver", valueOrDefault(event.getVersionName(), ""));

        JSONObject device = new JSONObject();
        device.put("brand", valueOrDefault(Build.BRAND, ""));
        device.put("checkCommonUse", "1");
        device.put("language", languageOrDefault(""));
        device.put("model", valueOrDefault(Build.MODEL, ""));
        device.put("oaid", oaidOrDefault(context, event.getStartupInstanceId()));
        device.put("system", systemOrDefault(""));

        JSONObject data = new JSONObject();
//        data.put("stageId", "" + event.getStageId());
//        data.put("stage", event.getStage().name());
//        data.put("status", event.getStatus().name());
//        data.put("resultCode", event.getResultCode());
        data.put("failureClass", event.getFailureClass());
        data.put("event", event.getStage().name()); // server
        data.put("eventId", "" + event.getStage().getId()); // server
        data.put("itemName", event.getStatus().name() + (isBlank(event.getResultCode()) ? "" : "_" + event.getResultCode())); // server
        data.put("sessionId", event.getSessionId());
        data.put("startupInstanceId", event.getStartupInstanceId());
        data.put("telemetryEventId", event.getTelemetryEventId());
        data.put("schemaVersion", 2);
        data.put("versionCode", event.getVersionCode());
        data.put("occurredAtMillis", event.getOccurredAtMillis());
        data.put("elapsedMs", event.getElapsedSinceStartMs());
        data.put("stageDurationMs", event.getStageDurationMs());
//        data.put("isFirstLaunch", event.isFirstLaunch());
//        data.put("isMainProcess", event.isMainProcess());
//        data.put("processName", event.getProcessName());
        data.put("activityName", event.getActivityName());
        data.put("activityResumedElapsedMs", event.getActivityResumedElapsedMs());
//        data.put("authorizationSource", event.getAuthorizationSource().name());
//        data.put("networkAuthorizationRequired", event.getNetworkAuthorizationRequired());

        JSONObject userEvent = new JSONObject();
        userEvent.put("eventType", TAG);
        userEvent.put("data", data);

        JSONObject adData = new JSONObject();
        userEvent.put("adPosition", "");

        JSONObject request = new JSONObject();
        request.put("channel", channel);
        request.put("device", device);
        request.put("gameUser", gameUser);
        request.put("adData", adData);
        request.put("productInfo", productInfo);
        request.put("userEvent", userEvent);
        return request;
    }

    private static String applicationLabel(Context context) {
        CharSequence label = context.getApplicationInfo().loadLabel(context.getPackageManager());
        return label.toString();
    }

    private static String languageOrDefault(String defaultValue) {
        String language = Locale.getDefault().toLanguageTag();
        return valueOrDefault(language, defaultValue);
    }

    private static String countryOrDefault(String defaultValue) {
        String country = Locale.getDefault().getCountry();
        return valueOrDefault(country, defaultValue).toLowerCase(Locale.ROOT);
    }

    private static String systemOrDefault(String defaultValue) {
        String release = Build.VERSION.RELEASE;
        if (isBlank(release) || Build.VERSION.SDK_INT <= 0) {
            return defaultValue;
        }
        return "android:" + release + ",api:" + Build.VERSION.SDK_INT;
    }

    private static String oaidOrDefault(Context context, String defaultValue) {
        try {
            String oaid = Settings.Secure.getString(
                    context.getContentResolver(), "oaid");
            return valueOrDefault(oaid, defaultValue);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static long retryAfterMillis(String value) {
        if (isBlank(value)) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value.trim())) * 1000L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
     * Shell-safe TLS setup for the startup reporter only.
     * It deliberately does not install a global security provider and does not
     * depend on the business-layer HttpManager.
     */
    private static final class StartupTls {
        static X509TrustManager conscryptTrustManager(Provider provider) throws Exception {
            TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX", provider);
            factory.init((KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager) {
                    return (X509TrustManager) manager;
                }
            }
            throw new IllegalStateException("No bundled Conscrypt X509TrustManager available");
        }
    }
}
