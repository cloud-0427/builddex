package io.github.xjc.jiagu;

import org.junit.Test;

import java.util.List;

import okhttp3.ConnectionSpec;
import okhttp3.Request;
import okhttp3.TlsVersion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class NetworkHelperHttpTest {
    @Test
    public void runtimeRequestsUseOkHttpDefaultConnectionPersistence() throws Exception {
        Request request = NetworkHelper.buildRequest("https://example.com/test", new byte[] {1});
        assertNull(request.header("Connection"));
        assertEquals("application/json, application/octet-stream", request.header("Accept"));
    }

    @Test
    public void runtimePrefersTls13AndFallsBackToTls12() {
        List<ConnectionSpec> specs = NetworkHelper.connectionSpecs();
        assertEquals(TlsVersion.TLS_1_3, specs.get(0).tlsVersions().get(0));
        assertEquals(TlsVersion.TLS_1_2, specs.get(1).tlsVersions().get(0));
        assertFalse(specs.get(2).isTls());
    }
}
