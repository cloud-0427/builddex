package io.github.xjc.dexreport;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

@DisableCachingByDefault(because = "Local random key material must not enter remote build cache")
public abstract class CreateLocalPayloadTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getPayload();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getManifest();
    @Input public abstract Property<String> getPackageName();
    @Input public abstract Property<Integer> getVersionCode();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract org.gradle.api.file.ConfigurableFileCollection getExistingAssets();
    @OutputDirectory public abstract DirectoryProperty getAssets();
    @TaskAction public void encrypt() throws Exception {
        for (java.io.File asset : getExistingAssets())
            if (asset.exists()) throw new IllegalStateException("Reserved local Payload asset already exists: " + asset);
        byte[] plain = Files.readAllBytes(getPayload().get().getAsFile().toPath());
        if (plain.length < 8 || plain.length > 128 * 1024 * 1024) throw new IllegalStateException("Local Payload exceeds memory budget");
        String manifest = Files.readString(getManifest().get().getAsFile().toPath());
        Files.createDirectories(getAssets().get().getAsFile().toPath().resolve("jiagu"));
        byte[] output = encode(plain, getPackageName().get(), getVersionCode().get(),
                manifest.contains("LocalAndroidXComponentFactory") ? "androidx" : "default");
        try { Files.write(getAssets().get().getAsFile().toPath().resolve("jiagu/local-payload.jgl"), output); }
        finally { Arrays.fill(plain, (byte) 0); Arrays.fill(output, (byte) 0); }
    }

    static byte[] encode(byte[] plain, String pkg, int version, String factory) throws Exception {
        byte[] key = new byte[32], a = new byte[32], b = new byte[32], nonce = new byte[12];
        SecureRandom random = new SecureRandom(); random.nextBytes(key); random.nextBytes(a); random.nextBytes(nonce);
        for (int i = 0; i < 32; i++) b[i] = (byte) (key[i] ^ a[i]);
        try {
            Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            String config = "{\"mode\":\"local\",\"cipherAlgorithm\":\"AES-256-GCM\",\"aadVersion\":\"JIAGU-LOCAL-ASSET-V1\","
                    + "\"packageName\":\"" + pkg + "\",\"versionCode\":" + version + ",\"plaintextLength\":" + plain.length
                    + ",\"plaintextSha256\":\"" + enc.encodeToString(MessageDigest.getInstance("SHA-256").digest(plain))
                    + "\",\"keyPartA\":\"" + enc.encodeToString(a) + "\",\"keyPartB\":\"" + enc.encodeToString(b)
                    + "\",\"nonce\":\"" + enc.encodeToString(nonce) + "\",\"factory\":\"" + factory + "\"}";
            byte[] configBytes = config.getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD("JIAGU-LOCAL-ASSET-V1".getBytes(StandardCharsets.UTF_8)); cipher.updateAAD(configBytes);
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(16 + configBytes.length + encrypted.length).putInt(0x4a474c41).putInt(1)
                    .putInt(configBytes.length).putInt(encrypted.length).put(configBytes).put(encrypted).array();
        } finally { Arrays.fill(key, (byte) 0); Arrays.fill(a, (byte) 0); Arrays.fill(b, (byte) 0); }
    }
}
