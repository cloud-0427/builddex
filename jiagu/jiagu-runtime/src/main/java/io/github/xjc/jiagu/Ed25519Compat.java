package io.github.xjc.jiagu;

import com.google.crypto.tink.subtle.Ed25519Verify;

import java.security.GeneralSecurityException;
import java.security.SignatureException;

/** Provider-independent Ed25519 verification for Android API 29+. */
final class Ed25519Compat {
    private Ed25519Compat() {}

    static void verify(byte[] publicKey, byte[] message, byte[] signature)
            throws SignatureException {
        try {
            new Ed25519Verify(publicKey).verify(signature, message);
        } catch (GeneralSecurityException error) {
            SignatureException wrapped = new SignatureException("invalid JWS signature");
            wrapped.initCause(error);
            throw wrapped;
        }
    }
}
