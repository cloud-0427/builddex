package lows.dgeon.ightr.jiagu;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import io.github.xjc.jiagu.JiaguStartupEvent;
import io.github.xjc.jiagu.JiaguStartupLogUploader;

/**
 * Minimal shell-safe startup event reporter.
 *
 * <p>The path follows the aiKeMeiTrackEvent endpoint convention. No AKM SDK
 * initialization or retry queue is used.</p>
 */
public final class StartupEventUploader implements JiaguStartupLogUploader {
    private static final String TAG = "Jiagu_EventUpload";

    private static final int TIMEOUT_MS = 15_000;

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

    @Override
    public void upload(Context appContext, JiaguStartupEvent event) {
        if (event == null || appContext == null) {
            return;
        }
        if (!event.isFirstLaunch() || !event.isMainProcess()) {
            return;
        }

        HttpURLConnection connection = null;
        try {
            byte[] body = createBody(appContext, event).toString()
                    .getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) new URL(EVENT_ENDPOINT).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int statusCode = connection.getResponseCode();
            Log.i(TAG, "Startup event: status=" + statusCode + " " + event);
        } catch (Exception error) {
            // Startup telemetry is best-effort and must never affect the shell.
            Log.w(TAG, "Startup event failed: " + event, error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
        device.put("oaid", oaidOrDefault(context, "123456"));
        device.put("system", systemOrDefault(""));

        JSONObject data = new JSONObject();
        data.put("event", event.getType().name());
        data.put("eventId", "eventId");
        data.put("itemName", "itemName");
        data.put("sessionId", event.getSessionId());
        data.put("occurredAtMillis", event.getOccurredAtMillis());
        data.put("elapsedMs", event.getElapsedMs());
        data.put("isFirstLaunch", event.isFirstLaunch());
        data.put("isMainProcess", event.isMainProcess());
        data.put("processName", event.getProcessName());
        data.put("authorizationSource", event.getAuthorizationSource().name());
        data.put("networkAuthorizationRequired", event.isNetworkAuthorizationRequired());

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
}
