package io.github.xjc.jiagu;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Offline-only payload decoder. It deliberately has no network, Keystore or identity inputs. */
public final class LocalPayloadCrypto {
    private LocalPayloadCrypto() { }

    public static ByteBuffer getLocalPayload(Context ignored, String runtimeConfig, ByteBuffer payload)
            throws Exception {
        JSONObject config = new JSONObject(runtimeConfig);
        if (config.getInt("configVersion") != 4 || !"local".equals(config.getString("mode"))
                || !"AES-256-GCM".equals(config.getString("cipherAlgorithm"))) {
            throw new SecurityException("invalid local runtime config");
        }
        byte[] key = Base64.decode(config.getString("keyPartA"), Base64.URL_SAFE | Base64.NO_PADDING);
        if (key.length != 32 || payload == null || !payload.isDirect()) {
            Arrays.fill(key, (byte) 0);
            throw new SecurityException("invalid local payload key or buffer");
        }
        ByteBuffer plaintext = null;
        try {
            ByteBuffer source = payload.duplicate(); source.position(0);
            if (source.remaining() < 72 || source.get() != 'J' || source.get() != 'G'
                    || source.get() != 'L' || source.get() != 'P' || source.getInt() != 2) {
                throw new SecurityException("invalid local payload header");
            }
            int encryptedLength = source.getInt();
            if (encryptedLength != source.remaining() || encryptedLength < 60) {
                throw new SecurityException("invalid local payload length");
            }
            byte[] keyPartB = new byte[32]; source.get(keyPartB);
            for (int i = 0; i < key.length; i++) key[i] ^= keyPartB[i];
            Arrays.fill(keyPartB, (byte) 0);
            byte[] nonce = new byte[12]; source.get(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            String aad = "LOCAL-PAYLOAD-V1\0" + config.getString("packageName") + "\0"
                    + config.getLong("versionCode") + "\0" + config.getString("payloadPlaintextSha256");
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            plaintext = ByteBuffer.allocateDirect(cipher.getOutputSize(source.remaining()));
            cipher.doFinal(source, plaintext); plaintext.flip();
            ByteBuffer result = plaintext.slice();
            if (!config.getString("payloadPlaintextSha256").equals(sha256(result.duplicate()))) {
                throw new SecurityException("local payload hash mismatch");
            }
            return result;
        } finally {
            Arrays.fill(key, (byte) 0);
            if (plaintext != null && !plaintext.hasRemaining()) { /* returned direct buffer is retained by caller */ }
        }
    }

    private static String sha256(ByteBuffer buffer) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] chunk = new byte[Math.min(8192, buffer.remaining())];
        while (buffer.hasRemaining()) { int n = Math.min(chunk.length, buffer.remaining()); buffer.get(chunk, 0, n); digest.update(chunk, 0, n); }
        Arrays.fill(chunk, (byte) 0);
        return Base64.encodeToString(digest.digest(), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
