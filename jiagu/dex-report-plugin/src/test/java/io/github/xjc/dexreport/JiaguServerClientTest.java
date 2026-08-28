package io.github.xjc.dexreport;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
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
        AtomicReference<String> connectionHeader = new AtomicReference<>();
        server.createContext("/api/v1/companies/acme/pack/auth-check", exchange -> {
            connectionHeader.set(exchange.getRequestHeaders().getFirst("Connection"));
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
            assertEquals("close", connectionHeader.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void createReleaseRetriesOnceWhenNoHttpResponseIsReceived() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> connectionHeader = new AtomicReference<>();
        Path payload = Files.createTempFile("jiagu-client-retry", ".jg3");
        Files.write(payload, "payload".getBytes(StandardCharsets.UTF_8));
        String payloadHash = JiaguServerClient.sha256(Files.readAllBytes(payload));
        String payloadKey = Base64.getEncoder().encodeToString(new byte[32]);
        server.createContext("/api/v1/companies/acme/pack/releases", exchange -> {
            connectionHeader.set(exchange.getRequestHeaders().getFirst("Connection"));
            if (requests.incrementAndGet() == 1) {
                exchange.close();
                return;
            }
            byte[] body = ("{\"code\":\"RELEASE_REUSED\",\"message\":\"ok\",\"details\":{" +
                    "\"releaseId\":\"release-1\",\"payloadId\":\"app-main\",\"payloadVersion\":1," +
                    "\"packageName\":\"com.example\",\"versionCode\":1," +
                    "\"certificateSha256Digests\":[\"certificate\"]," +
                    "\"certificateSetSha256\":\"certificate-set\"," +
                    "\"businessDexSha256\":\"business\",\"resourcesSha256\":\"resources\"," +
                    "\"nativeLibsSha256\":\"native\",\"releaseBuildSha256\":\"release-build\"," +
                    "\"payloadPlaintextSha256\":\"" + payloadHash + "\"," +
                    "\"payloadKeyVersion\":1,\"localPayloadSize\":0," +
                    "\"payloadKey\":\"" + payloadKey + "\",\"status\":\"DRAFT\"," +
                    "\"operation\":\"REUSED\",\"keyRotated\":false}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            JiaguServerClient client = new JiaguServerClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "acme", "valid-key");
            JiaguServerClient.Release release = client.createRelease(
                    payload.toFile(), "app-main", 1, "com.example", 1,
                    Collections.singletonList("certificate"), "business", "resources", "native");
            assertEquals("release-1", release.releaseId);
            assertEquals(2, requests.get());
            assertEquals("close", connectionHeader.get());
        } finally {
            Files.deleteIfExists(payload);
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
