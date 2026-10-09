package io.github.xjc.dexreport;

import java.io.IOException;
import java.security.*;
import java.util.Base64;

final class LocalBuildHash {
    private LocalBuildHash() {}
    static String sha256(byte[] value) throws IOException {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
}
