package io.github.xjc.jiagu;

import org.junit.Test;

import java.net.HttpURLConnection;
import java.net.URL;

import static org.junit.Assert.assertEquals;

public class NetworkHelperHttpTest {
    @Test
    public void runtimeRequestsDisableConnectionReuse() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        NetworkHelper.disableConnectionReuse(connection);
        assertEquals("close", connection.connectionHeader);
    }

    private static final class RecordingConnection extends HttpURLConnection {
        String connectionHeader;

        RecordingConnection() throws Exception {
            super(new URL("http://127.0.0.1/"));
        }

        @Override
        public void setRequestProperty(String key, String value) {
            if ("Connection".equalsIgnoreCase(key)) {
                connectionHeader = value;
            }
        }

        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
}
