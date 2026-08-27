package io.github.xjc.dexreport;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class JiaguServerClientTest {
    @Test
    public void localMachineNameIsPresentAndLimitedToSixtyFourCharacters() {
        String machineName = JiaguServerClient.localMachineName();
        assertTrue(!machineName.isEmpty());
        assertTrue(machineName.length() <= 64);
    }

    @Test
    public void authCheckPreservesFourHundredResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/companies/acme/pack/auth-check", exchange -> {
            byte[] body = ("{\"code\":\"COMPANY_UNAUTHORIZED\","
                    + "\"message\":\"invalid company API key\",\"details\":{}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            JiaguServerClient client = new JiaguServerClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "acme", "wrong-key");
            try {
                client.verifyCompanyAccess();
                fail("expected company authentication to fail");
            } catch (IOException error) {
                assertTrue(error.getMessage(), error.getMessage().contains("HTTP 401"));
                assertTrue(error.getMessage(), error.getMessage().contains("COMPANY_UNAUTHORIZED"));
                assertTrue(error.getMessage(), error.getMessage().contains("invalid company API key"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void multipartEarlyRejectionStillUsesFourHundredResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/companies/acme/pack/auth-check", exchange -> {
            byte[] body = ("{\"code\":\"COMPANY_AUTHORIZED\",\"message\":\"ok\","
                    + "\"details\":{\"companyId\":\"acme\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/companies/acme/pack/releases", exchange -> {
            byte[] body = ("{\"code\":\"PAYLOAD_TOO_LARGE\","
                    + "\"message\":\"payload exceeds configured limit\",\"details\":{}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(413, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path payload = Files.createTempFile("jiagu-client-test", ".jg3");
        Files.write(payload, new byte[2 * 1024 * 1024]);
        try {
            JiaguServerClient client = new JiaguServerClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "acme", "valid-key");
            client.verifyCompanyAccess();
            try {
                client.createRelease(payload.toFile(), "app-main", 1, "com.example", 1,
                        Collections.singletonList("certificate"), "business", "resources", "native");
                fail("expected payload upload to fail");
            } catch (IOException error) {
                assertTrue(error.getMessage(), error.getMessage().contains("HTTP 413"));
                assertTrue(error.getMessage(), error.getMessage().contains("PAYLOAD_TOO_LARGE"));
                assertTrue(error.getMessage(), error.getMessage().contains("payload exceeds configured limit"));
            }
        } finally {
            Files.deleteIfExists(payload);
            server.stop(0);
        }
    }
}
