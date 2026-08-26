package io.github.xjc.dexreport;

import com.android.build.api.dsl.ApkSigningConfig;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Base64;

final class SigningCertificate {
    private SigningCertificate() {}

    static String sha256Base64Url(ApkSigningConfig config) {
        File storeFile = config.getStoreFile();
        String storePassword = config.getStorePassword();
        String keyAlias = config.getKeyAlias();
        if (storeFile == null || storePassword == null || keyAlias == null) {
            throw new IllegalStateException("Jiagu signingConfig is incomplete");
        }
        try {
            String type = config.getStoreType();
            KeyStore store = KeyStore.getInstance(type == null || type.isEmpty()
                    ? KeyStore.getDefaultType() : type);
            try (FileInputStream input = new FileInputStream(storeFile)) {
                store.load(input, storePassword.toCharArray());
            }
            Certificate certificate = store.getCertificate(keyAlias);
            if (certificate == null) {
                throw new IllegalStateException("Signing certificate alias not found: " + keyAlias);
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Jiagu cannot read signing certificate from " + storeFile, e);
        }
    }
}
