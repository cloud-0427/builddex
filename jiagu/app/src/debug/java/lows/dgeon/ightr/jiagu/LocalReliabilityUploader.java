package lows.dgeon.ightr.jiagu;

import android.content.Context;
import io.github.xjc.jiagu.local.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Debug-only localhost transport. adb reverse maps this port to the test controller. */
public final class LocalReliabilityUploader implements LocalEventUploader {
    public void upload(Context context, LocalStartupEvent event) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:18765/events").openConnection();
        c.setConnectTimeout(1000); c.setReadTimeout(1000);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Idempotency-Key", event.eventId);
        byte[] payload = event.toJson().toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        try {
            try (OutputStream out = c.getOutputStream()) { out.write(payload); }
            int status = c.getResponseCode();
            if (status != 200) throw new LocalUploadException("HTTP_" + status, status >= 500 || status == 429, null);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buffer = new byte[512]; int n;
                while ((n = in.read(buffer)) != -1) { if (body.size() + n > 4096) throw new IOException("ACK_TOO_LARGE"); body.write(buffer,0,n); }
            }
            if (!event.eventId.equals(new JSONObject(body.toString("UTF-8")).getString("telemetryEventId")))
                throw new LocalUploadException("ACK_MISMATCH", true, null);
        } catch (IOException error) { throw new LocalUploadException("NETWORK_FAILURE", true, error); }
        finally { c.disconnect(); }
    }
}
