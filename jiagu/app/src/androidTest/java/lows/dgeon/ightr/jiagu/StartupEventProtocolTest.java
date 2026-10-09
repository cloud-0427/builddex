package lows.dgeon.ightr.jiagu;

import android.content.Context;
import org.json.JSONObject;
import java.lang.reflect.*;
import java.util.*;

/** Exercises the actual app serializer without calling its network transport. */
final class StartupEventProtocolTest {
    static void verify(Context context) throws Exception {
        ClassLoader loader = context.getClassLoader();
        Class<?> type = Class.forName("io.github.xjc.jiagu.local.LocalStartupEvent", true, loader);
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class, String.class,
                String.class, String.class, long.class, long.class, long.class, String.class, long.class, String.class);
        constructor.setAccessible(true);
        Method body = Class.forName("lows.dgeon.ightr.jiagu.StartupEventUploader", true, loader)
                .getDeclaredMethod("createBody", Context.class, type);
        body.setAccessible(true);
        String[] stages = {"STARTUP_ATTEMPT", "PAYLOAD_DECRYPT", "LOCAL_PAYLOAD_READ", "BUSINESS_DEX_PARSE",
                "BUSINESS_CLASSLOADER_CREATE", "REAL_APPLICATION_CREATE", "EVENT_REPORTER_READY", "FIRST_ACTIVITY_RESUMED"};
        String[] ids = {"1", "7", "1001", "1002", "1003", "1004", "1005", "1006"};
        for (int i = 0; i < stages.length; i++) {
            Object event = constructor.newInstance("session", "unique-id", stages[i], "FAILED", "TEST_CODE",
                    1234L, 15L, 5L, context.getPackageName(), 24L, "process");
            JSONObject request = (JSONObject) body.invoke(null, context, event);
            Set<String> roots = new HashSet<>(); request.keys().forEachRemaining(roots::add);
            require(roots.equals(new HashSet<>(Arrays.asList("channel", "device", "gameUser", "adData", "productInfo", "userEvent"))), "request envelope");
            require("7".equals(request.getJSONObject("channel").getString("id")), "channel");
            require("370".equals(request.getJSONObject("productInfo").getString("appId")), "appId");
            require("wacky.frenzy.blast.cann".equals(request.getJSONObject("productInfo").getString("bundle")), "bundle");
            JSONObject user = request.getJSONObject("userEvent"), data = user.getJSONObject("data");
            require("JG_Event".equals(user.getString("eventType")) && "".equals(user.getString("adPosition")), "userEvent envelope");
            require(ids[i].equals(data.getString("eventId")) && data.get("eventId") instanceof String, "stage eventId");
            require(stages[i].equals(data.getString("event")), "event name");
            require("unique-id".equals(data.getString("telemetryEventId")), "unique event ID");
            require("FAILED_TEST_CODE".equals(data.getString("itemName")), "itemName");
            require(data.getLong("elapsedMs") == 15 && data.getLong("occurredAtMillis") == 1234 && data.getLong("stageDurationMs") == 5, "timings");
            require(data.getInt("schemaVersion") == 3 && "local".equals(data.getString("mode")), "local schema");
            require(!data.has("stage") && !data.has("elapsedSinceStartMs") && !data.has("startupInstanceId"), "no renamed/fictional fields");
            require(request.toString().equals(((JSONObject) body.invoke(null, context, event)).toString()), "retry body stability");
        }
        Object started = constructor.newInstance("session", "unique-id", "STARTUP_ATTEMPT", "STARTED", "",
                1234L, 15L, 0L, context.getPackageName(), 24L, "process");
        JSONObject data = ((JSONObject) body.invoke(null, context, started)).getJSONObject("userEvent").getJSONObject("data");
        require("STARTED".equals(data.getString("itemName")), "empty result code");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("Legacy event protocol: " + message);
    }
}
