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
import org.json.JSONArray;
import org.conscrypt.Conscrypt;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SignatureException;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.TlsVersion;

/** Device-bound authorization client used by the native shell during startup. */
public final class NetworkHelper {
    private static final String TAG = "Jiagu_Network";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String PREFS = "jiagu_device_credential_v1";
    private static final int HTTP_TIMEOUT_MS = 15_000;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 128 * 1024 * 1024;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private NetworkHelper() {}

    private static final class HttpClientHolder {
        private static final OkHttpClient INSTANCE = createHttpClient();
    }

    /**
     * Authorizes the device and decrypts the APK-local payload into one direct buffer.
     * The native ELF mapping is consumed directly, avoiding whole-payload JNI byte[] copies.
     */
    public static ByteBuffer getAuthorizedPayload(Context context, String runtimeConfigJson,
                                                  ByteBuffer localPayload) {
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

            String credential = loadCredential(context, config, app.actualCertificateSha256, deviceId,
                    signPublicKey, wrapPublicKey);
            if (credential == null) {
                credential = enroll(context, config, app.actualCertificateSha256,
                        signing, signPublicKey, wrapPublicKey, deviceId);
                saveCredential(context, config, app.actualCertificateSha256, credential);
            }

            Authorization authorization = loadAuthorization(context, config, deviceId, wrapping.getPrivate());
            if (authorization == null) {
                authorization = authorize(
                        context, config, signing, wrapping.getPrivate(), deviceId, credential);
                saveAuthorization(context, config, authorization);
            }

            return decryptLocalPayload(config, authorization.payloadKey, localPayload);
        } catch (Throwable error) {
            Log.e(TAG, "Device authorization failed", error);
            return null;
        } finally {
            StrictMode.setThreadPolicy(previous);
        }
    }

    private static String enroll(Context context, Config config, String actualCertificateSha256,
                                 KeyPair signing,
                                 String signPublicKey, String wrapPublicKey,
                                 String deviceId) throws Exception {
        JSONObject challenge = challenge(config, "ENROLL");
        String message = canonical("ENROLL-V2", config.companyId,
                challenge.getString("challengeId"), challenge.getString("challenge"),
                config.releaseId, config.packageName, Long.toString(config.versionCode),
                actualCertificateSha256, config.certificateSetSha256,
                config.releaseBuildSha256, signPublicKey, wrapPublicKey);
        String integrityToken = integrityToken(context, config, sha256(bytes(message)));
        JSONObject request = new JSONObject()
                .put("challengeId", challenge.getString("challengeId"))
                .put("challenge", challenge.getString("challenge"))
                .put("releaseId", config.releaseId)
                .put("actualCertificateSha256", actualCertificateSha256)
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
        String message = canonical("AUTHORIZE-V2", config.companyId,
                challenge.getString("challengeId"), challenge.getString("challenge"),
                config.releaseId, sha256(bytes(credential)), deviceId,
                config.releaseBuildSha256, Long.toString(config.payloadKeyVersion));
        String integrityToken = integrityToken(context, config, sha256(bytes(message)));
        JSONObject request = new JSONObject()
                .put("challengeId", challenge.getString("challengeId"))
                .put("challenge", challenge.getString("challenge"))
                .put("releaseId", config.releaseId)
                .put("deviceCredential", credential)
                .put("integrityToken", integrityToken)
                .put("deviceSignature", sign(signing.getPrivate(), bytes(message)));
        JSONObject response = postJson(config.basePath() + "/unpack/authorize", request);
        Log.i(TAG, "Authorization accepted for release " + config.releaseId);
        String grant = response.getString("grant");
        String wrapped = response.getString("wrappedPayloadKey");
        JSONObject claims = verifyJws(config, grant);
        verifyGrant(config, claims, deviceId, wrapped);
        if (!"RSA-OAEP-SHA1".equals(response.getString("wrapAlgorithm"))) {
            throw new SecurityException("wrap algorithm mismatch");
        }
        byte[] key = decryptWrappedKey(wrapPrivate, wrapped);
        return new Authorization(grant, claims, wrapped, key);
    }

    private static byte[] decryptWrappedKey(PrivateKey wrapPrivate, String wrapped) throws Exception {
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
        return key;
    }

    private static ByteBuffer decryptLocalPayload(Config config, byte[] payloadKey,
                                                  ByteBuffer localPayload) throws Exception {
        ByteBuffer plaintext = null;
        boolean succeeded = false;
        try {
            if (localPayload == null || !localPayload.isDirect()) {
                throw new SecurityException("local payload must use direct memory");
            }
            ByteBuffer container = localPayload.duplicate();
            container.position(0);
            if (container.remaining() != config.localPayloadSize ||
                    !config.localCiphertextSha256.equals(sha256(container.duplicate())) ||
                    container.remaining() < 40 || container.get(0) != 'J' || container.get(1) != 'G' ||
                    container.get(2) != 'L' || container.get(3) != 'P') {
                throw new SecurityException("invalid local payload binding");
            }
            container.position(4);
            int version = container.getInt();
            int encryptedLength = container.getInt();
            if (version != 1 || encryptedLength < 28 || encryptedLength != container.remaining()) {
                throw new SecurityException("invalid local payload header");
            }
            byte[] nonce = new byte[12];
            container.get(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(payloadKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(bytes(canonical("LOCAL-PAYLOAD-V3", config.companyId,
                    config.releaseId, config.payloadId,
                    Long.toString(config.payloadVersion), config.packageName,
                    Long.toString(config.versionCode), config.certificateSetSha256,
                    config.releaseBuildSha256,
                    config.payloadPlaintextSha256, Long.toString(config.payloadKeyVersion))));
            plaintext = ByteBuffer.allocateDirect(cipher.getOutputSize(container.remaining()));
            cipher.doFinal(container, plaintext);
            plaintext.flip();
            ByteBuffer result = plaintext.slice();
            if (!config.payloadPlaintextSha256.equals(sha256(result.duplicate()))) {
                clear(result);
                throw new SecurityException("payload plaintext hash mismatch");
            }
            succeeded = true;
            return result;
        } finally {
            if (!succeeded && plaintext != null) {
                ByteBuffer sensitive = plaintext.duplicate();
                sensitive.position(0);
                sensitive.limit(sensitive.capacity());
                clear(sensitive);
            }
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
        Ed25519Compat.verify(rawPublicKey, bytes(parts[0] + "." + parts[1]), b64decode(parts[2]));
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
                !config.certificateSha256Digests.contains(claims.getString("certificateSha256")) ||
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
                !config.certificateSha256Digests.contains(claims.getString("certificateSha256")) ||
                !config.certificateSetSha256.equals(claims.getString("certificateSetSha256")) ||
                !config.releaseBuildSha256.equals(claims.getString("releaseBuildSha256")) ||
                !config.payloadPlaintextSha256.equals(claims.getString("payloadPlaintextSha256")) ||
                !config.localCiphertextSha256.equals(claims.getString("localCiphertextSha256")) ||
                config.payloadKeyVersion != claims.getLong("payloadKeyVersion") ||
                !sha256(bytes(wrapped)).equals(claims.getString("wrappedPayloadKeySha256")) ||
                claims.getLong("issuedAt") > System.currentTimeMillis() / 1000L + 60L ||
                claims.getLong("expiresAt") < System.currentTimeMillis() / 1000L) {
            throw new SecurityException("payload grant binding mismatch");
        }
    }

    private static String loadCredential(Context context, Config config, String actualCertificateSha256,
                                         String deviceId,
                                         String signPublicKey, String wrapPublicKey) {
        String key = credentialPreferenceKey(config, actualCertificateSha256);
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

    private static void saveCredential(Context context, Config config,
                                       String actualCertificateSha256, String token) {
        String key = credentialPreferenceKey(config, actualCertificateSha256);
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        preferences.edit().putString(key, token).apply();
    }

    private static String credentialPreferenceKey(Config config, String actualCertificateSha256) {
        return sha256Unchecked(bytes(canonical("DEVICE-CREDENTIAL-CACHE-V2", config.companyId,
                config.packageName, actualCertificateSha256)));
    }

    private static Authorization loadAuthorization(Context context, Config config,
                                                   String deviceId, PrivateKey wrapPrivate) {
        String key = sha256Unchecked(bytes(config.companyId + "|" + config.releaseId + "|auth"));
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String grant = prefs.getString(key, null);
        String wrapped = prefs.getString(key + ".wrapped", null);
        if (grant == null || wrapped == null) {
            return null;
        }
        try {
            JSONObject claims = verifyJws(config, grant);
            if (claims.getLong("expiresAt") < System.currentTimeMillis() / 1000L + 30L) {
                return null;
            }
            verifyGrant(config, claims, deviceId, wrapped);
            byte[] payloadKey = decryptWrappedKey(wrapPrivate, wrapped);
            return new Authorization(grant, claims, wrapped, payloadKey);
        } catch (Exception e) {
            prefs.edit().remove(key).remove(key + ".wrapped").apply();
            return null;
        }
    }

    private static void saveAuthorization(Context context, Config config, Authorization auth) {
        String key = sha256Unchecked(bytes(config.companyId + "|" + config.releaseId + "|auth"));
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        preferences.edit()
                .putString(key, auth.grant)
                .putString(key + ".wrapped", auth.wrappedKey)
                .apply();
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
        JSONObject envelope = new JSONObject(new String(response, StandardCharsets.UTF_8));
        JSONObject details = envelope.optJSONObject("details");
        if (details == null) {
            throw new SecurityException("Jiagu server returned an invalid response envelope");
        }
        return details;
    }

    private static byte[] request(String url, byte[] body, int maxResponseBytes) throws Exception {
        Request request = buildRequest(url, body);
        int status;
        byte[] responseBytes;
        try (Response response = HttpClientHolder.INSTANCE.newCall(request).execute()) {
            status = response.code();
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                responseBytes = new byte[0];
            } else {
                long contentLength = responseBody.contentLength();
                if (contentLength > maxResponseBytes) {
                    throw new SecurityException("Jiagu response exceeds size limit");
                }
                responseBytes = readLimited(responseBody.byteStream(), maxResponseBytes);
            }
        }
        if (status < 200 || status >= 300) {
            String code = "HTTP_" + status;
            String message = "request rejected";
            try {
                JSONObject error = new JSONObject(new String(responseBytes, StandardCharsets.UTF_8));
                code = error.optString("code", code);
                message = error.optString("message", message);
            } catch (Exception ignored) {
                // Keep the bounded generic error; never log authorization tokens or bodies.
            }
            throw new SecurityException("Jiagu server rejected request: " + code + ": " + message);
        }
        return responseBytes;
    }

    static Request buildRequest(String url, byte[] body) {
        return new Request.Builder()
                .url(url)
                .header("Accept", "application/json, application/octet-stream")
                .post(RequestBody.create(body, JSON_MEDIA_TYPE))
                .build();
    }

    static List<ConnectionSpec> connectionSpecs() {
        ConnectionSpec tls13 = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_3)
                .build();
        ConnectionSpec tls12 = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build();
        return Arrays.asList(tls13, tls12, ConnectionSpec.CLEARTEXT);
    }

    private static OkHttpClient createHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .connectionSpecs(connectionSpecs())
                .retryOnConnectionFailure(true);
        try {
            Provider provider = Conscrypt.newProvider();
            X509TrustManager trustManager = defaultTrustManager();
            SSLContext sslContext = SSLContext.getInstance("TLS", provider);
            sslContext.init(null, new TrustManager[] {trustManager}, null);
            builder.sslSocketFactory(sslContext.getSocketFactory(), trustManager);
            Log.i(TAG, "Using bundled Conscrypt TLS provider: " + provider.getName());
        } catch (Throwable error) {
            // Unsupported ABI or provider initialization failure must not make the app unstartable.
            // OkHttp will use Android's platform TLS provider as a compatibility fallback.
            Log.w(TAG, "Bundled Conscrypt unavailable; using Android platform TLS", error);
        }
        return builder.build();
    }

    private static X509TrustManager defaultTrustManager() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                return (X509TrustManager) manager;
            }
        }
        throw new IllegalStateException("No system X509TrustManager available");
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

    private static String sha256(ByteBuffer value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(value);
        return b64(digest.digest());
    }

    private static void clear(ByteBuffer value) {
        for (int i = value.position(); i < value.limit(); i++) {
            value.put(i, (byte) 0);
        }
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
        final String wrappedKey;
        final byte[] payloadKey;

        Authorization(String grant, JSONObject grantClaims, String wrappedKey, byte[] payloadKey) {
            this.grant = grant;
            this.grantClaims = grantClaims;
            this.wrappedKey = wrappedKey;
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
        List<String> certificateSha256Digests;
        String certificateSetSha256;
        String businessDexSha256;
        String resourcesSha256;
        String nativeLibsSha256;
        String releaseBuildSha256;
        String payloadPlaintextSha256;
        String localCiphertextSha256;
        int localPayloadSize;
        long payloadKeyVersion;
        String serverKeyId;
        String serverPublicKey;
        String wrapAlgorithm;
        String integrityMode;
        long integrityCloudProjectNumber;

        static Config parse(String json) throws Exception {
            JSONObject value = new JSONObject(json);
            if (value.getInt("configVersion") != 3) {
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
            config.certificateSha256Digests = new ArrayList<>();
            JSONArray certificates = value.getJSONArray("certificateSha256Digests");
            for (int i = 0; i < certificates.length(); i++) {
                config.certificateSha256Digests.add(certificates.getString(i));
            }
            Collections.sort(config.certificateSha256Digests);
            config.certificateSetSha256 = value.getString("certificateSetSha256");
            config.businessDexSha256 = value.getString("businessDexSha256");
            config.resourcesSha256 = value.getString("resourcesSha256");
            config.nativeLibsSha256 = value.getString("nativeLibsSha256");
            config.releaseBuildSha256 = value.getString("releaseBuildSha256");
            config.payloadPlaintextSha256 = value.getString("payloadPlaintextSha256");
            config.localCiphertextSha256 = value.getString("localCiphertextSha256");
            config.localPayloadSize = value.getInt("localPayloadSize");
            config.payloadKeyVersion = value.getLong("payloadKeyVersion");
            config.serverKeyId = value.getString("serverKeyId");
            config.serverPublicKey = value.getString("serverPublicKey");
            config.wrapAlgorithm = value.getString("wrapAlgorithm");
            config.integrityMode = value.getString("integrityMode");
            config.integrityCloudProjectNumber = value.optLong("integrityCloudProjectNumber", 0L);
            if (config.serverUrl.isEmpty() || config.companyId.isEmpty() ||
                    config.releaseId.isEmpty() || config.serverPublicKey.isEmpty() ||
                    config.certificateSha256Digests.isEmpty() || config.releaseBuildSha256.isEmpty() ||
                    config.localCiphertextSha256.isEmpty() || config.localPayloadSize < 40 ||
                    !"RSA-OAEP-SHA1".equals(config.wrapAlgorithm)) {
                throw new SecurityException("incomplete RuntimeConfig");
            }
            return config;
        }

        String basePath() {
            return serverUrl + "/api/v1/companies/" + companyId;
        }

        void verifyApp(AppIdentity app) {
            if (!packageName.equals(app.packageName) || versionCode != app.versionCode) {
                throw new SecurityException("installed application identity mismatch");
            }
            for (String certificate : app.currentCertificateSha256Digests) {
                if (certificateSha256Digests.contains(certificate)) {
                    app.actualCertificateSha256 = certificate;
                    return;
                }
            }
            throw new SecurityException("installed signing certificate is not allowed");
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
        List<String> currentCertificateSha256Digests;
        String actualCertificateSha256;

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
            identity.currentCertificateSha256Digests = new ArrayList<>();
            for (Signature signature : signatures) {
                identity.currentCertificateSha256Digests.add(sha256(signature.toByteArray()));
            }
            Collections.sort(identity.currentCertificateSha256Digests);
            return identity;
        }
    }
}
