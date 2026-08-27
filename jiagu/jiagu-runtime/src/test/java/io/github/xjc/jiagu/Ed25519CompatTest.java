package io.github.xjc.jiagu;

import org.junit.Test;

import java.security.SignatureException;

import static org.junit.Assert.fail;

public class Ed25519CompatTest {
    // RFC 8032 section 7.1, test vector 1 (empty message).
    private static final byte[] PUBLIC_KEY = hex(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    private static final byte[] SIGNATURE = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                    + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");

    @Test
    public void verifiesKnownEd25519SignatureWithoutPlatformProvider() throws Exception {
        Ed25519Compat.verify(PUBLIC_KEY, new byte[0], SIGNATURE);
    }

    @Test
    public void rejectsModifiedEd25519Signature() throws Exception {
        byte[] modified = SIGNATURE.clone();
        modified[0] ^= 1;
        try {
            Ed25519Compat.verify(PUBLIC_KEY, new byte[0], modified);
            fail("expected modified signature to be rejected");
        } catch (SignatureException expected) {
            // Expected.
        }
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
