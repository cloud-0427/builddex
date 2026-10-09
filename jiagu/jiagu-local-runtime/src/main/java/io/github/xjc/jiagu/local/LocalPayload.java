package io.github.xjc.jiagu.local;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Context-free, bounded decoder. No native library, files, network or Keystore. */
public final class LocalPayload {
    public static final int MAX_PLAIN = 128 * 1024 * 1024;
    public static final int MAX_ASSET = MAX_PLAIN + 65536 + 32;
    private LocalPayload() {}
    public interface Observer {
        void terminal(String stage, String status, String code, long durationMs);
    }

    public static final class Decoded {
        public final ByteBuffer[] dex;
        public final String packageName;
        public final long versionCode;
        Decoded(ByteBuffer[] dex, String packageName, long versionCode) {
            this.dex = dex; this.packageName = packageName; this.versionCode = versionCode;
        }
    }

    public static Decoded decode(byte[] asset, String expectedPackage) throws Exception {
        return decode(asset, expectedPackage, null);
    }

    public static Decoded decode(byte[] asset, String expectedPackage, Observer observer) throws Exception {
        String stage = "LOCAL_PAYLOAD_READ";
        long started = System.nanoTime();
        byte[] key = null, part = null, nonce = null;
        ByteBuffer plain = null;
        boolean success = false;
        try {
        if (asset.length < 32 || asset.length > MAX_ASSET) throw invalid("LENGTH_INVALID");
        ByteBuffer b = ByteBuffer.wrap(asset);
        if (b.getInt() != 0x4a474c41 || b.getInt() != 1) throw invalid("FORMAT_UNSUPPORTED");
        int configLength = b.getInt(), cipherLength = b.getInt();
        if (configLength < 2 || configLength > 65536 || cipherLength < 16 ||
                (long) configLength + cipherLength != b.remaining()) throw invalid("LENGTH_INVALID");
        byte[] configBytes = new byte[configLength]; b.get(configBytes);
        Map<String, String> c = config(new String(configBytes, StandardCharsets.UTF_8));
        if (!"local".equals(c.get("mode")) || !"AES-256-GCM".equals(c.get("cipherAlgorithm")) ||
                !"JIAGU-LOCAL-ASSET-V1".equals(c.get("aadVersion"))) throw invalid("FORMAT_UNSUPPORTED");
        if (!expectedPackage.equals(c.get("packageName"))) throw invalid("PACKAGE_MISMATCH");
        int plainLength = Integer.parseInt(c.get("plaintextLength"));
        if (plainLength < 8 || plainLength > MAX_PLAIN || cipherLength != (long) plainLength + 16)
            throw invalid("MEMORY_BUDGET_EXCEEDED");
        long versionCode = Long.parseLong(c.get("versionCode"));
        if (versionCode < 0) throw invalid("VERSION_INVALID");
            key = bytes(c, "keyPartA", 32);
            part = bytes(c, "keyPartB", 32); nonce = bytes(c, "nonce", 12);
            observe(observer, stage, "SUCCEEDED", "PAYLOAD_READ", started);
            stage = "PAYLOAD_DECRYPT"; started = System.nanoTime();
            for (int i = 0; i < 32; i++) key[i] ^= part[i];
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD("JIAGU-LOCAL-ASSET-V1".getBytes(StandardCharsets.UTF_8));
            cipher.updateAAD(configBytes);
            plain = ByteBuffer.allocateDirect(plainLength);
            try { cipher.doFinal(b.slice(), plain); }
            catch (javax.crypto.AEADBadTagException cause) {
                throw new SecurityException("LOCAL_PAYLOAD_AUTH_FAILED", cause);
            }
            plain.flip();
            MessageDigest hash = MessageDigest.getInstance("SHA-256"); hash.update(plain.duplicate());
            if (!MessageDigest.isEqual(hash.digest(), bytes(c, "plaintextSha256", 32))) throw invalid("HASH_MISMATCH");
            observe(observer, stage, "SUCCEEDED", "PAYLOAD_VERIFIED", started);
            stage = "BUSINESS_DEX_PARSE"; started = System.nanoTime();
            ByteBuffer[] dex = dexBuffers(plain);
            observe(observer, stage, "SUCCEEDED", "DEX_VALIDATED", started);
            success = true;
            return new Decoded(dex, expectedPackage, versionCode);
        } catch (Exception | Error error) {
            observe(observer, stage, "FAILED", failureCode(error, stage), started);
            throw error;
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (part != null) Arrays.fill(part, (byte) 0);
            if (nonce != null) Arrays.fill(nonce, (byte) 0);
            if (!success && plain != null) { plain.clear(); while (plain.hasRemaining()) plain.put((byte) 0); }
        }
    }

    private static void observe(Observer observer, String stage, String status, String code, long started) {
        try {
            if (observer != null) observer.terminal(stage, status, code,
                    Math.max(0, (System.nanoTime() - started) / 1_000_000));
        } catch (Throwable ignored) { /* Telemetry cannot alter decode outcomes. */ }
    }

    public static String failureCode(Throwable error, String stage) {
        String code = error.getMessage();
        if (code != null && Arrays.asList("LOCAL_PAYLOAD_MISSING", "LOCAL_PAYLOAD_DUPLICATE",
                "LOCAL_PAYLOAD_FORMAT_UNSUPPORTED", "LOCAL_PAYLOAD_LENGTH_INVALID", "LOCAL_PAYLOAD_CONFIG_INVALID",
                "LOCAL_PAYLOAD_PACKAGE_MISMATCH", "LOCAL_PAYLOAD_VERSION_INVALID", "LOCAL_PAYLOAD_FACTORY_UNSUPPORTED",
                "LOCAL_PAYLOAD_MEMORY_BUDGET_EXCEEDED", "LOCAL_PAYLOAD_AUTH_FAILED", "LOCAL_PAYLOAD_HASH_MISMATCH",
                "LOCAL_PAYLOAD_DEX_TABLE_INVALID", "LOCAL_PAYLOAD_DEX_HEADER_INVALID").contains(code)) return code;
        if (error instanceof OutOfMemoryError) return "LOCAL_ALLOCATION_FAILED";
        if ("LOCAL_PAYLOAD_READ".equals(stage)) return error instanceof NumberFormatException || error instanceof IllegalArgumentException
                ? "LOCAL_PAYLOAD_CONFIG_INVALID" : "LOCAL_PAYLOAD_READ_FAILED";
        if ("PAYLOAD_DECRYPT".equals(stage)) return "LOCAL_PAYLOAD_DECRYPT_FAILED";
        if ("BUSINESS_DEX_PARSE".equals(stage)) return "LOCAL_DEX_PARSE_FAILED";
        return "LOCAL_CLASSLOADER_CREATE_FAILED";
    }

    static Map<String, String> config(String text) {
        // Fixed flat schema: ASCII strings and nonnegative integers, no escaping or nested objects.
        String keys = "mode,cipherAlgorithm,aadVersion,packageName,versionCode,plaintextLength,plaintextSha256,keyPartA,keyPartB,nonce,factory";
        Set<String> required = new HashSet<>(Arrays.asList(keys.split(",")));
        Map<String, String> result = new HashMap<>();
        Pattern entry = Pattern.compile("\\s*\"([A-Za-z][A-Za-z0-9]*)\"\\s*:\\s*(?:\"([A-Za-z0-9_.-]+)\"|([0-9]+))\\s*");
        if (!text.startsWith("{") || !text.endsWith("}")) throw invalid("CONFIG_INVALID");
        String body = text.substring(1, text.length() - 1);
        for (String token : body.split(",", -1)) {
            Matcher m = entry.matcher(token);
            if (!m.matches() || !required.remove(m.group(1))) throw invalid("CONFIG_INVALID");
            result.put(m.group(1), m.group(2) == null ? m.group(3) : m.group(2));
        }
        if (!required.isEmpty()) throw invalid("CONFIG_INVALID");
        if (!"default".equals(result.get("factory")) && !"androidx".equals(result.get("factory")))
            throw invalid("FACTORY_UNSUPPORTED");
        return result;
    }

    private static byte[] bytes(Map<String, String> c, String field, int size) {
        byte[] value = Base64.getUrlDecoder().decode(c.get(field));
        if (value.length != size) throw invalid("CONFIG_INVALID");
        return value;
    }

    public static ByteBuffer[] dexBuffers(ByteBuffer plain) {
        ByteBuffer b = plain.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (b.remaining() < 8 || b.getInt() != 0x4a473400) throw invalid("DEX_TABLE_INVALID");
        int count = b.getInt();
        if (count < 1 || count > 128 || 8L + 12L * count > plain.remaining()) throw invalid("DEX_TABLE_INVALID");
        int body = 8 + 12 * count, next = 0;
        ByteBuffer[] result = new ByteBuffer[count];
        for (int i = 0; i < count; i++) {
            int offset = b.getInt(), length = b.getInt(), raw = b.getInt();
            if (offset != next || length < 112 || raw != length || (long) body + offset + length > plain.limit())
                throw invalid("DEX_TABLE_INVALID");
            ByteBuffer dex = plain.duplicate(); dex.position(body + offset); dex.limit(body + offset + length);
            dex = dex.slice().order(ByteOrder.LITTLE_ENDIAN);
            if (dex.get(0) != 'd' || dex.get(1) != 'e' || dex.get(2) != 'x' || dex.get(3) != '\n' ||
                    dex.get(7) != 0 || dex.getInt(32) != length || dex.getInt(36) != 112 ||
                    dex.getInt(40) != 0x12345678) throw invalid("DEX_HEADER_INVALID");
            result[i] = dex; next += length;
        }
        if ((long) body + next != plain.limit()) throw invalid("DEX_TABLE_INVALID");
        return result;
    }

    private static SecurityException invalid(String code) { return new SecurityException("LOCAL_PAYLOAD_" + code); }
}
