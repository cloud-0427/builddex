package io.github.xjc.jiagu;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.StrictMode;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.StandardIntegrityManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/** Device-bound authorization client used by the native shell during startup. */
public final class NetworkHelper {
    private static final String TAG = "Jiagu_Network";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String PREFS = "jiagu_device_credential_v1";
    private static final int HTTP_TIMEOUT_MS = 15_000;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 128 * 1024 * 1024;

    private NetworkHelper() {}

    /** Executes ENROLL/AUTHORIZE/download and returns verified JG3 plaintext only. */
    public static byte[] getAuthorizedPayload(Context context, String runtimeConfigJson) {
        StrictMode.ThreadPolicy previous = StrictMode.getThreadPolicy();
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().permitAll().build());
        try {
            Config config = Config.parse(runtimeConfigJson);
            AppIdentity app = AppIdentity.read(context);
            config.verifyApp(app);
            KeyPair signing = getOrCreateSigningKey(config);
            KeyPair wrapping = getOrCreateWrappingKey(config);
            String signPublicKey = b64(signing.getPublic().getEncoded());
            String wrapPublicKey = b64(wrapping.getPublic().getEncoded());
            String deviceId = sha256(concat(
                    signing.getPublic().getEncoded(), wrapping.getPublic().getEncoded()));

            String credential = loadCredential(context, config, deviceId,
                    signPublicKey, wrapPublicKey);
            if (credential == null) {
                credential = enroll(context, config, signing, signPublicKey,
                        wrapPublicKey, deviceId);
                saveCredential(context, config, credential);
            }
            Authorization authorization = authorize(
                    context, config, signing, wrapping.getPrivate(), deviceId, credential);
            byte[] container = postBytes(config.downloadUrl(),
                    new JSONObject().put("grant", authorization.grant).toString());
            return decryptContainer(config, authorization.grantClaims,
                    authorization.payloadKey, container);
        } catch (Throwable error) {
            Log.e(TAG, "Device authorization failed", error);
            return null;
        } finally {
            StrictMode.setThreadPolicy(previous);
        }
    }

    private static String enroll(Context context, Config config, KeyPair signing,
                                 String signPublicKey, String wrapPublicKey,
                                 String deviceId) throws Exception {
        JSONObject challenge = challenge(config, "ENROLL");
        String message = canonical("ENROLL-V1", config.companyId,
                challenge.getString("challengeId"), challenge.getString("challenge"),
                config.releaseId, config.packageName, Long.toString(config.versionCode),
                config.certificateSha256, signPublicKey, wrapPublicKey);
        String integrityToken = integrityToken(context, config, sha256(bytes(message)));
        JSONObject request = new JSONObject()
                .put("challengeId", challenge.getString("challengeId"))
                .put("challenge", challenge.getString("challenge"))
                .put("releaseId", config.releaseId)
                .put("signPublicKey", signPublicKey)
                .put("wrapPublicKey", wrapPublicKey)
                .put("integrityToken", integrityToken)
                .put("deviceSignature", sign(signing.getPrivate(), bytes(message)));
        JSONObject response = postJson(config.basePath() + "/unpack/enroll", request);
        if (!deviceId.equals(response.getString("deviceId"))) {
            throw new SecurityException("deviceId binding mismatch");
        }
        String credential = response.getString("deviceCredential");
        JSONObject claims = verifyJws(config, credential);
        verifyCredential(config, claims, deviceId, signPublicKey, wrapPublicKey);
        return credential;
    }

    private static Authorization authorize(Context context, Config config,
                                           KeyPair signing, PrivateKey wrapPrivate,
                                           String deviceId, String credential) throws Exception {
        JSONObject challenge = challenge(config, "AUTHORIZE");
        String message = canonical("AUTHORIZE-V1", config.companyId,
                challenge.getString("challengeId"), challenge.getString("challenge"),
                config.releaseId, sha256(bytes(credential)), deviceId);
        String integrityToken = integrityToken(context, config, sha256(bytes(message)));
        JSONObject request = new JSONObject()
                .put("challengeId", challenge.getString("challengeId"))
                .put("challenge", challenge.getString("challenge"))
                .put("releaseId", config.releaseId)
                .put("deviceCredential", credential)
                .put("integrityToken", integrityToken)
                .put("deviceSignature", sign(signing.getPrivate(), bytes(message)));
        JSONObject response = postJson(config.basePath() + "/unpack/authorize", request);
        Log.i(TAG, "Authorize response: " + response.toString());
        String grant = response.getString("grant");
        String wrapped = response.getString("wrappedPayloadKey");
        JSONObject claims = verifyJws(config, grant);
        verifyGrant(config, claims, deviceId, wrapped);
        if (!"RSA-OAEP".equals(response.getString("wrapAlgorithm"))) {
            throw new SecurityException("wrap algorithm mismatch");
        }
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        // Android KeyStore RSA-OAEP implementation defaults MGF1 to SHA-1.
        // For maximum compatibility on API 29-34, we use SHA-1 for both main and MGF1 digests.
        OAEPParameterSpec oaep = new OAEPParameterSpec("SHA-1", "MGF1",
                MGF1ParameterSpec.SHA1,
                PSource.PSpecified.DEFAULT);
        cipher.init(Cipher.DECRYPT_MODE, wrapPrivate, oaep);
        byte[] key = cipher.doFinal(b64decode(wrapped));
        if (key.length != 32) {
            Arrays.fill(key, (byte) 0);
            throw new SecurityException("invalid device payload key length");
        }
        return new Authorization(grant, claims, key);
    }

    private static byte[] decryptContainer(Config config, JSONObject grant,
                                           byte[] payloadKey, byte[] container) throws Exception {
        try {
            if (container.length < 12 || container[0] != 'J' || container[1] != 'G' ||
                    container[2] != 'P' || container[3] != 'D') {
                throw new SecurityException("invalid JGPD magic");
            }
            ByteBuffer buffer = ByteBuffer.wrap(container);
            buffer.position(4);
            int version = buffer.getInt();
            int encryptedLength = buffer.getInt();
            if (version != 1 || encryptedLength < 28 || encryptedLength != buffer.remaining()) {
                throw new SecurityException("invalid JGPD header");
            }
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(payloadKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(bytes(canonical("DEVICE-PAYLOAD-V1", config.companyId,
                    grant.getString("deviceId"), config.releaseId, config.payloadId,
                    Long.toString(config.payloadVersion), config.packageName,
                    Long.toString(config.versionCode), config.certificateSha256,
                    config.payloadPlaintextSha256, Long.toString(config.payloadKeyVersion))));
            byte[] plaintext = cipher.doFinal(ciphertext);
            if (!config.payloadPlaintextSha256.equals(sha256(plaintext))) {
                Arrays.fill(plaintext, (byte) 0);
                throw new SecurityException("payload plaintext hash mismatch");
            }
            return plaintext;
        } finally {
            Arrays.fill(payloadKey, (byte) 0);
        }
    }

    private static JSONObject challenge(Config config, String purpose) throws Exception {
        JSONObject response = postJson(config.basePath() + "/unpack/challenges",
                new JSONObject().put("purpose", purpose));
        if (!purpose.equals(response.getString("purpose")) ||
                response.getLong("expiresAt") < System.currentTimeMillis() / 1000L) {
            throw new SecurityException("invalid or expired challenge");
        }
        return response;
    }

    private static String integrityToken(Context context, Config config,
                                         String requestHash) throws Exception {
        if ("disabled".equals(config.integrityMode)) {
            return "";
        }
        if (!"google".equals(config.integrityMode) || config.integrityCloudProjectNumber <= 0) {
            throw new SecurityException("invalid Play Integrity runtime configuration");
        }
        StandardIntegrityManager manager = IntegrityManagerFactory.createStandard(
                context.getApplicationContext());
        StandardIntegrityManager.StandardIntegrityTokenProvider provider = Tasks.await(
                manager.prepareIntegrityToken(
                        StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                                .setCloudProjectNumber(config.integrityCloudProjectNumber)
                                .build()), 60, TimeUnit.SECONDS);
        StandardIntegrityManager.StandardIntegrityToken token = Tasks.await(
                provider.request(StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                        .setRequestHash(requestHash).build()), 60, TimeUnit.SECONDS);
        return token.token();
    }

    private static JSONObject verifyJws(Config config, String token) throws Exception {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new SignatureException("invalid JWS format");
        }
        JSONObject header = new JSONObject(new String(b64decode(parts[0]), StandardCharsets.UTF_8));
        if (!"EdDSA".equals(header.getString("alg")) ||
                !config.serverKeyId.equals(header.getString("kid"))) {
            throw new SignatureException("unexpected JWS header");
        }
        byte[] rawPublicKey = b64decode(config.serverPublicKey);
        if (rawPublicKey.length != 32) {
            throw new SignatureException("invalid Ed25519 public key");
        }
        byte[] prefix = new byte[]{0x30, 0x2a, 0x30, 0x05, 0x06, 0x03,
                0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
        PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(concat(prefix, rawPublicKey)));
        java.security.Signature verifier = java.security.Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(bytes(parts[0] + "." + parts[1]));
        if (!verifier.verify(b64decode(parts[2]))) {
            throw new SignatureException("invalid JWS signature");
        }
        return new JSONObject(new String(b64decode(parts[1]), StandardCharsets.UTF_8));
    }

    private static void verifyCredential(Config config, JSONObject claims, String deviceId,
                                         String signPublicKey, String wrapPublicKey) throws Exception {
        if (!"DEVICE_CREDENTIAL".equals(claims.getString("type")) ||
                !config.companyId.equals(claims.getString("companyId")) ||
                !deviceId.equals(claims.getString("deviceId")) ||
                !signPublicKey.equals(claims.getString("signPublicKey")) ||
                !wrapPublicKey.equals(claims.getString("wrapPublicKey")) ||
                !config.packageName.equals(claims.getString("packageName")) ||
                !config.certificateSha256.equals(claims.getString("certificateSha256")) ||
                claims.getLong("expiresAt") < System.currentTimeMillis() / 1000L) {
            throw new SecurityException("device credential binding mismatch");
        }
    }

    private static void verifyGrant(Config config, JSONObject claims, String deviceId,
                                    String wrapped) throws Exception {
        String localWrapHash = sha256(getOrCreateWrappingKey(config).getPublic().getEncoded());
        if (!"PAYLOAD_GRANT".equals(claims.getString("type")) ||
                !config.companyId.equals(claims.getString("companyId")) ||
                !deviceId.equals(claims.getString("deviceId")) ||
                !localWrapHash.equals(claims.getString("deviceWrapKeySha256")) ||
                !config.releaseId.equals(claims.getString("releaseId")) ||
                !config.payloadId.equals(claims.getString("payloadId")) ||
                config.payloadVersion != claims.getLong("payloadVersion") ||
                !config.packageName.equals(claims.getString("packageName")) ||
                config.versionCode != claims.getLong("versionCode") ||
                !config.certificateSha256.equals(claims.getString("certificateSha256")) ||
                !config.payloadPlaintextSha256.equals(claims.getString("payloadPlaintextSha256")) ||
                config.payloadKeyVersion != claims.getLong("payloadKeyVersion") ||
                !sha256(bytes(wrapped)).equals(claims.getString("wrappedPayloadKeySha256")) ||
                claims.getLong("issuedAt") > System.currentTimeMillis() / 1000L + 60L ||
                claims.getLong("expiresAt") < System.currentTimeMillis() / 1000L) {
            throw new SecurityException("payload grant binding mismatch");
        }
    }

    private static String loadCredential(Context context, Config config, String deviceId,
                                         String signPublicKey, String wrapPublicKey) {
        String key = sha256Unchecked(bytes(config.companyId + "|" + config.releaseId));
        String token = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key, null);
        if (token == null) {
            return null;
        }
        try {
            verifyCredential(config, verifyJws(config, token), deviceId,
                    signPublicKey, wrapPublicKey);
            return token;
        } catch (Exception invalid) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply();
            return null;
        }
    }

    private static void saveCredential(Context context, Config config, String token) {
        String key = sha256Unchecked(bytes(config.companyId + "|" + config.releaseId));
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        preferences.edit().putString(key, token).apply();
    }

    private static KeyPair getOrCreateSigningKey(Config config) throws Exception {
        String alias = alias("sign", config);
        KeyStore store = loadKeyStore();
        if (!store.containsAlias(alias)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE);
            generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build());
            generator.generateKeyPair();
            store = loadKeyStore();
        }
        return new KeyPair(store.getCertificate(alias).getPublicKey(),
                (PrivateKey) store.getKey(alias, null));
    }

    private static KeyPair getOrCreateWrappingKey(Config config) throws Exception {
        String alias = alias("wrap", config);
        KeyStore store = loadKeyStore();
        if (!store.containsAlias(alias)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE);
            generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(3072)
                    .setDigests(KeyProperties.DIGEST_SHA1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .build());
            generator.generateKeyPair();
            store = loadKeyStore();
        }
        return new KeyPair(store.getCertificate(alias).getPublicKey(),
                (PrivateKey) store.getKey(alias, null));
    }

    private static KeyStore loadKeyStore() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
        store.load(null);
        return store;
    }

    private static String alias(String type, Config config) {
        return "jiagu." + type + ".v2." + sha256Unchecked(
                bytes(config.companyId + "|" + config.packageName)).substring(0, 22);
    }

    private static String sign(PrivateKey key, byte[] message) throws Exception {
        java.security.Signature signature = java.security.Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(message);
        return b64(signature.sign());
    }

    private static JSONObject postJson(String url, JSONObject body) throws Exception {
        byte[] response = request(url, body.toString().getBytes(StandardCharsets.UTF_8),
                MAX_JSON_BYTES);
        return new JSONObject(new String(response, StandardCharsets.UTF_8));
    }

    private static byte[] postBytes(String url, String json) throws Exception {
        return request(url, json.getBytes(StandardCharsets.UTF_8), MAX_PAYLOAD_BYTES);
    }

    private static byte[] request(String url, byte[] body, int maxResponseBytes) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(HTTP_TIMEOUT_MS);
        connection.setReadTimeout(HTTP_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, application/octet-stream");
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        byte[] response = readLimited(input, maxResponseBytes);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new SecurityException("Jiagu server rejected request with HTTP " + status);
        }
        return response;
    }

    private static byte[] readLimited(InputStream input, int limit) throws Exception {
        if (input == null) {
            return new byte[0];
        }
        try (InputStream closeable = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int count;
            while ((count = closeable.read(buffer)) != -1) {
                total += count;
                if (total > limit) {
                    throw new SecurityException("Jiagu response exceeds size limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String canonical(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            byte[] encoded = bytes(value);
            builder.append(encoded.length).append(':').append(value).append('\n');
        }
        return builder.toString();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static String sha256(byte[] value) throws Exception {
        return b64(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String sha256Unchecked(byte[] value) {
        try {
            return sha256(value);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String b64(byte[] value) {
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] b64decode(String value) {
        return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static final class Authorization {
        final String grant;
        final JSONObject grantClaims;
        final byte[] payloadKey;

        Authorization(String grant, JSONObject grantClaims, byte[] payloadKey) {
            this.grant = grant;
            this.grantClaims = grantClaims;
            this.payloadKey = payloadKey;
        }
    }

    private static final class Config {
        String serverUrl;
        String companyId;
        String releaseId;
        String payloadId;
        long payloadVersion;
        String packageName;
        long versionCode;
        String certificateSha256;
        String payloadPlaintextSha256;
        long payloadKeyVersion;
        String serverKeyId;
        String serverPublicKey;
        String integrityMode;
        long integrityCloudProjectNumber;

        static Config parse(String json) throws Exception {
            JSONObject value = new JSONObject(json);
            if (value.getInt("configVersion") != 1) {
                throw new SecurityException("unsupported RuntimeConfig version");
            }
            Config config = new Config();
            config.serverUrl = trimSlash(value.getString("serverUrl"));
            config.companyId = value.getString("companyId");
            config.releaseId = value.getString("releaseId");
            config.payloadId = value.getString("payloadId");
            config.payloadVersion = value.getLong("payloadVersion");
            config.packageName = value.getString("packageName");
            config.versionCode = value.getLong("versionCode");
            config.certificateSha256 = value.getString("certificateSha256");
            config.payloadPlaintextSha256 = value.getString("payloadPlaintextSha256");
            config.payloadKeyVersion = value.getLong("payloadKeyVersion");
            config.serverKeyId = value.getString("serverKeyId");
            config.serverPublicKey = value.getString("serverPublicKey");
            config.integrityMode = value.getString("integrityMode");
            config.integrityCloudProjectNumber = value.optLong("integrityCloudProjectNumber", 0L);
            if (config.serverUrl.isEmpty() || config.companyId.isEmpty() ||
                    config.releaseId.isEmpty() || config.serverPublicKey.isEmpty()) {
                throw new SecurityException("incomplete RuntimeConfig");
            }
            return config;
        }

        String basePath() {
            return serverUrl + "/api/v1/companies/" + companyId;
        }

        String downloadUrl() {
            return basePath() + "/unpack/download";
        }

        void verifyApp(AppIdentity app) {
            if (!packageName.equals(app.packageName) || versionCode != app.versionCode ||
                    !certificateSha256.equals(app.certificateSha256)) {
                throw new SecurityException("installed application identity mismatch");
            }
        }

        private static String trimSlash(String value) {
            while (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            return value;
        }
    }

    private static final class AppIdentity {
        String packageName;
        long versionCode;
        String certificateSha256;

        static AppIdentity read(Context context) throws Exception {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] signatures = info.signingInfo.getApkContentsSigners();
            if (signatures == null || signatures.length == 0) {
                throw new SecurityException("APK signing certificate is unavailable");
            }
            AppIdentity identity = new AppIdentity();
            identity.packageName = context.getPackageName();
            identity.versionCode = info.getLongVersionCode();
            identity.certificateSha256 = sha256(signatures[0].toByteArray());
            return identity;
        }
    }
}
