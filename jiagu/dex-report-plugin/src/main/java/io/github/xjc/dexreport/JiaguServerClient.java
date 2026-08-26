package io.github.xjc.dexreport;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JiaguServerClient {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final String serverUrl;
    private final String companyId;
    private final String companyApiKey;

    JiaguServerClient(String serverUrl, String companyId, String companyApiKey) {
        this.serverUrl = trimSlash(required(serverUrl, "serverUrl"));
        this.companyId = required(companyId, "companyId");
        this.companyApiKey = required(companyApiKey, "companyApiKey");
    }

    PublicConfig getPublicConfig() throws IOException {
        String body = request("GET", companyPath() + "/public-config", null, null, false);
        PublicConfig result = new PublicConfig();
        result.companyId = stringField(body, "companyId");
        result.serverKeyId = stringField(body, "serverKeyId");
        result.serverPublicKey = stringField(body, "serverPublicKey");
        result.integrityMode = stringField(body, "integrityMode");
        result.integrityCloudProjectNumber = longField(body, "integrityCloudProjectNumber", 0L);
        if (!companyId.equals(result.companyId) || !"EdDSA".equals(stringField(body, "grantAlgorithm"))) {
            throw new IOException("Jiagu public-config binding is invalid");
        }
        return result;
    }

    Release createRelease(File payload, String payloadId, long payloadVersion,
                          String packageName, long versionCode,
                          String certificateSha256) throws IOException {
        String boundary = "----Jiagu" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        textPart(body, boundary, "payloadId", payloadId);
        textPart(body, boundary, "payloadVersion", Long.toString(payloadVersion));
        textPart(body, boundary, "packageName", packageName);
        textPart(body, boundary, "versionCode", Long.toString(versionCode));
        textPart(body, boundary, "certificateSha256", certificateSha256);
        writeAscii(body, "--" + boundary + "\r\n");
        writeAscii(body, "Content-Disposition: form-data; name=\"payload\"; filename=\"payload.jg3\"\r\n");
        writeAscii(body, "Content-Type: application/octet-stream\r\n\r\n");
        try (InputStream input = new FileInputStream(payload)) {
            copy(input, body);
        }
        writeAscii(body, "\r\n--" + boundary + "--\r\n");

        String response = request("POST", companyPath() + "/pack/releases",
                "multipart/form-data; boundary=" + boundary, body.toByteArray(), true);
        Release release = new Release();
        release.releaseId = stringField(response, "releaseId");
        release.payloadId = stringField(response, "payloadId");
        release.payloadVersion = longField(response, "payloadVersion", -1);
        release.packageName = stringField(response, "packageName");
        release.versionCode = longField(response, "versionCode", -1);
        release.certificateSha256 = stringField(response, "certificateSha256");
        release.plaintextSha256 = stringField(response, "plaintextSha256");
        release.payloadKeyVersion = longField(response, "payloadKeyVersion", -1);
        release.status = stringField(response, "status");
        String localHash = sha256(Files.readAllBytes(payload.toPath()));
        if (!payloadId.equals(release.payloadId) || payloadVersion != release.payloadVersion ||
                !packageName.equals(release.packageName) || versionCode != release.versionCode ||
                !certificateSha256.equals(release.certificateSha256) ||
                !localHash.equals(release.plaintextSha256) || !"DRAFT".equals(release.status)) {
            throw new IOException("Jiagu release response does not match uploaded payload");
        }
        return release;
    }

    void publish(String releaseId) throws IOException {
        String response = request("POST", companyPath() + "/pack/releases/" + encode(releaseId) + "/publish",
                "application/json", "{}".getBytes(StandardCharsets.UTF_8), true);
        if (!releaseId.equals(stringField(response, "releaseId")) ||
                !"PUBLISHED".equals(stringField(response, "status"))) {
            throw new IOException("Jiagu release publish response is invalid");
        }
    }

    private String companyPath() {
        return "/api/v1/companies/" + encode(companyId);
    }

    private String request(String method, String path, String contentType,
                           byte[] body, boolean companyAuth) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(serverUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Jiagu-Gradle-Plugin/1");
        if (companyAuth) {
            connection.setRequestProperty("X-Company-Key", companyApiKey);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = stream == null ? "" : new String(readAll(stream), StandardCharsets.UTF_8);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            String code = optionalStringField(response, "code");
            String message = optionalStringField(response, "message");
            if (code.isEmpty() && message.isEmpty() && !response.isEmpty()) {
                message = response.trim();
                if (message.length() > 1024) {
                    message = message.substring(0, 1024) + "...";
                }
            }
            throw new IOException("Jiagu server returned HTTP " + status +
                    (code.isEmpty() ? "" : " " + code) +
                    (message.isEmpty() ? "" : ": " + message));
        }
        return response;
    }

    private static void textPart(ByteArrayOutputStream output, String boundary,
                                 String name, String value) throws IOException {
        writeAscii(output, "--" + boundary + "\r\n");
        writeAscii(output, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        output.write(value.getBytes(StandardCharsets.UTF_8));
        writeAscii(output, "\r\n");
    }

    private static void writeAscii(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream closeable = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(closeable, output);
            return output.toByteArray();
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static String stringField(String json, String name) throws IOException {
        String result = optionalStringField(json, name);
        if (result.isEmpty()) {
            throw new IOException("Jiagu server response is missing " + name + ". Response: " + json);
        }
        return result;
    }

    private static String optionalStringField(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) +
                "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(json);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static long longField(String json, String name, long fallback) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) +
                "\\\"\\s*:\\s*(-?[0-9]+)").matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : fallback;
    }

    static String sha256(byte[] data) throws IOException {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Jiagu " + name + " is required");
        }
        return value.trim();
    }

    private static String trimSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static final class PublicConfig {
        String companyId;
        String serverKeyId;
        String serverPublicKey;
        String integrityMode;
        long integrityCloudProjectNumber;
    }

    static final class Release {
        String releaseId;
        String payloadId;
        long payloadVersion;
        String packageName;
        long versionCode;
        String certificateSha256;
        String plaintextSha256;
        long payloadKeyVersion;
        String status;
    }
}
