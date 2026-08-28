package io.github.xjc.dexreport;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JiaguServerClient {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final String serverUrl;
    private final String companyId;
    private final String companyApiKey;
    private final String packer;

    JiaguServerClient(String serverUrl, String companyId, String companyApiKey) {
        this.serverUrl = trimSlash(required(serverUrl, "serverUrl"));
        this.companyId = required(companyId, "companyId");
        this.companyApiKey = required(companyApiKey, "companyApiKey");
        this.packer = localMachineName();
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

    void verifyCompanyAccess() throws IOException {
        String response = request("GET", companyPath() + "/pack/auth-check", null, null, true);
        String authorizedCompanyId = stringField(response, "companyId");
        if (!companyId.equals(authorizedCompanyId)) {
            throw new IOException("Jiagu company auth-check binding is invalid");
        }
    }

    Release createRelease(File payload, String payloadId, long payloadVersion,
                          String packageName, long versionCode,
                          List<String> certificateSha256Digests,
                          String businessDexSha256, String resourcesSha256,
                          String nativeLibsSha256) throws IOException {
        String localHash = sha256(Files.readAllBytes(payload.toPath()));
        String requestJson = "{" +
                "\"payloadId\":" + json(payloadId) + "," +
                "\"payloadVersion\":" + payloadVersion + "," +
                "\"packageName\":" + json(packageName) + "," +
                "\"versionCode\":" + versionCode + "," +
                "\"packer\":" + json(packer) + "," +
                "\"certificateSha256Digests\":" + jsonArray(certificateSha256Digests) + "," +
                "\"businessDexSha256\":" + json(businessDexSha256) + "," +
                "\"resourcesSha256\":" + json(resourcesSha256) + "," +
                "\"nativeLibsSha256\":" + json(nativeLibsSha256) + "," +
                "\"payloadPlaintextSha256\":" + json(localHash) + "}";
        String response = request("POST", companyPath() + "/pack/releases",
                "application/json", requestJson.getBytes(StandardCharsets.UTF_8), true);
        Release release = new Release();
        release.releaseId = stringField(response, "releaseId");
        release.payloadId = stringField(response, "payloadId");
        release.payloadVersion = longField(response, "payloadVersion", -1);
        release.packageName = stringField(response, "packageName");
        release.versionCode = longField(response, "versionCode", -1);
        release.certificateSha256Digests = stringArrayField(response, "certificateSha256Digests");
        release.certificateSetSha256 = stringField(response, "certificateSetSha256");
        release.businessDexSha256 = stringField(response, "businessDexSha256");
        release.resourcesSha256 = stringField(response, "resourcesSha256");
        release.nativeLibsSha256 = stringField(response, "nativeLibsSha256");
        release.releaseBuildSha256 = stringField(response, "releaseBuildSha256");
        release.plaintextSha256 = stringField(response, "payloadPlaintextSha256");
        release.payloadKeyVersion = longField(response, "payloadKeyVersion", -1);
        release.localCiphertextSha256 = optionalStringField(response, "localCiphertextSha256");
        release.localPayloadSize = longField(response, "localPayloadSize", 0);
        try {
            release.payloadKey = Base64.getDecoder().decode(stringField(response, "payloadKey"));
        } catch (IllegalArgumentException error) {
            throw new IOException("Jiagu payload key is not valid Base64", error);
        }
        if (release.payloadKey.length != 32) throw new IOException("Jiagu payload key must contain 32 bytes");
        release.status = stringField(response, "status");
        release.operation = stringField(response, "operation");
        release.keyRotated = booleanField(response, "keyRotated", false);
        boolean statusOk = "DRAFT".equals(release.status) || "PUBLISHED".equals(release.status);
        if (!payloadId.equals(release.payloadId) || payloadVersion != release.payloadVersion ||
                !packageName.equals(release.packageName) || versionCode != release.versionCode ||
                !sortedCopy(certificateSha256Digests).equals(release.certificateSha256Digests) ||
                !businessDexSha256.equals(release.businessDexSha256) ||
                !resourcesSha256.equals(release.resourcesSha256) ||
                !nativeLibsSha256.equals(release.nativeLibsSha256) ||
                !localHash.equals(release.plaintextSha256) || !statusOk) {
            throw new IOException(String.format("Jiagu release response mismatch. " +
                    "Expected: [pkg=%s, ver=%d, hash=%s, status=DRAFT|PUBLISHED]. " +
                    "Got: [pkg=%s, ver=%d, hash=%s, status=%s]",
                    packageName, versionCode, localHash,
                    release.packageName, release.versionCode, release.plaintextSha256, release.status));
        }
        return release;
    }

    Release sealRelease(Release release, String ciphertextSha256, long payloadSize) throws IOException {
        String body = "{\"localCiphertextSha256\":" + json(ciphertextSha256) +
                ",\"localPayloadSize\":" + payloadSize + "}";
        String response = request("POST", companyPath() + "/pack/releases/" + encode(release.releaseId) + "/seal",
                "application/json", body.getBytes(StandardCharsets.UTF_8), true);
        if (!release.releaseId.equals(stringField(response, "releaseId")) ||
                !ciphertextSha256.equals(stringField(response, "localCiphertextSha256")) ||
                payloadSize != longField(response, "localPayloadSize", -1)) {
            throw new IOException("Jiagu local payload seal response mismatch");
        }
        release.localCiphertextSha256 = ciphertextSha256;
        release.localPayloadSize = payloadSize;
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

    static String localMachineName() {
        String value = "";
        try {
            value = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // Fall back to the conventional environment variables below.
        }
        if (value == null || value.trim().isEmpty()) value = System.getenv("COMPUTERNAME");
        if (value == null || value.trim().isEmpty()) value = System.getenv("HOSTNAME");
        value = value == null ? "unknown" : value.trim();
        return value.length() <= 64 ? value : value.substring(0, 64);
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
        IOException requestBodyError = null;
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            } catch (IOException error) {
                // The server may reject a streaming request before consuming
                // its body. Still ask for the HTTP response below so a 4xx JSON
                // envelope is preferred over the lower-level socket error.
                requestBodyError = error;
            }
        }
        int status;
        try {
            status = connection.getResponseCode();
        } catch (IOException error) {
            connection.disconnect();
            throw new IOException("Jiagu server request failed before an HTTP response was received: "
                    + method + " " + path + ": " + error.getMessage(),
                    requestBodyError == null ? error : requestBodyError);
        }
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
            String diagnostic = "Jiagu server returned HTTP " + status +
                    (code.isEmpty() ? "" : " " + code) +
                    (message.isEmpty() ? "" : ": " + message);
            if ("PUBLISHED_VERSION_MODIFIED".equals(code)) {
                diagnostic += ". This packageName/versionCode is already published; increase versionCode " +
                        "or give debug builds a different applicationIdSuffix";
                try {
                    List<String> changed = stringArrayField(response, "changedComponents");
                    if (!changed.isEmpty()) diagnostic += ". Changed components: " + changed;
                } catch (IOException ignored) {
                    // The stable code is sufficient when details are absent.
                }
            } else if ("REVOKED_VERSION_REUSE_FORBIDDEN".equals(code)) {
                diagnostic += ". Increase versionCode before rebuilding";
            }
            throw new IOException(diagnostic);
        }
        if (requestBodyError != null) {
            throw new IOException("Jiagu request body upload failed: " + method + " " + path
                    + ": " + requestBodyError.getMessage(), requestBodyError);
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

    private static boolean booleanField(String json, String name, boolean fallback) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) +
                "\\\"\\s*:\\s*(true|false)").matcher(json);
        return matcher.find() ? Boolean.parseBoolean(matcher.group(1)) : fallback;
    }

    private static List<String> stringArrayField(String json, String name) throws IOException {
        Matcher field = Pattern.compile("\\\"" + Pattern.quote(name) +
                "\\\"\\s*:\\s*\\[([^]]*)]", Pattern.DOTALL).matcher(json);
        if (!field.find()) {
            throw new IOException("Jiagu server response is missing " + name + ". Response: " + json);
        }
        List<String> values = new ArrayList<>();
        Matcher item = Pattern.compile("\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(field.group(1));
        while (item.find()) {
            values.add(item.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        Collections.sort(values);
        return values;
    }

    private static List<String> sortedCopy(List<String> values) {
        List<String> result = new ArrayList<>(values);
        Collections.sort(result);
        return result;
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

    private static String jsonArray(List<String> values) {
        StringBuilder result = new StringBuilder("[");
        for (String value : values) {
            if (result.length() > 1) result.append(',');
            result.append(json(value));
        }
        return result.append(']').toString();
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
        List<String> certificateSha256Digests;
        String certificateSetSha256;
        String businessDexSha256;
        String resourcesSha256;
        String nativeLibsSha256;
        String releaseBuildSha256;
        String plaintextSha256;
        String localCiphertextSha256;
        long localPayloadSize;
        byte[] payloadKey;
        long payloadKeyVersion;
        String status;
        String operation;
        boolean keyRotated;
    }
}
