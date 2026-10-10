package io.github.xjc.dexreport;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VerifyServicesTaskTest {
    @Test public void reportsShellPayloadClassCollisions() {
        assertEquals(Collections.singleton("k.a"), VerifyServicesTask.duplicateClassNames(
                Arrays.asList("k.a", "payload.Only"),
                Arrays.asList("k.a", "shell.Only")));
    }

    @Test public void acceptsDisjointClassSpaces() {
        assertTrue(VerifyServicesTask.duplicateClassNames(
                Collections.singleton("payload.Only"),
                Collections.singleton("shell.Only")).isEmpty());
    }
    @Test public void readsBundleServicesFromBaseRootOnly() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("services", ".aab");
        try {
            try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(file))) {
                out.putNextEntry(new java.util.jar.JarEntry("base/root/META-INF/services/example.Service"));
                out.write("example.Provider\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
                out.putNextEntry(new java.util.jar.JarEntry("META-INF/services/example.Wrong"));
                out.write("example.WrongProvider\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
            ServiceDescriptors descriptors = ServiceDescriptors.read(file, "base/root/");
            assertEquals(Collections.singleton("example.Service"), descriptors.entries.keySet());
            assertEquals(Collections.singleton("example.Provider"), descriptors.entries.get("example.Service"));
        } finally { java.nio.file.Files.deleteIfExists(file); }
    }
    @Test public void verifiesBaseBundleAndRejectsMissingDexOrChangedCiphertext() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("bundle-verification");
        VerifyServicesTask task = org.gradle.testfixtures.ProjectBuilder.builder().withProjectDir(dir.toFile())
                .build().getTasks().create("verifyBundle", VerifyServicesTask.class);
        java.nio.file.Path descriptors = dir.resolve("services.jar"), payload = dir.resolve("payload.jg4"), asset = dir.resolve("asset.jgl");
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(descriptors))) {}
        java.nio.file.Files.write(payload, java.nio.ByteBuffer.allocate(8).putInt(0x4a473400).putInt(0).array());
        java.nio.file.Files.write(asset, new byte[]{1, 2, 3});
        task.getDescriptors().set(descriptors.toFile()); task.getPayload().set(payload.toFile()); task.getEncryptedAsset().set(asset.toFile());
        for (int scenario = 0; scenario < 4; scenario++) {
            java.nio.file.Path bundle = dir.resolve("fixture" + scenario + ".aab");
            try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(bundle))) {
                for (String name : Arrays.asList("BundleConfig.pb", "base/manifest/AndroidManifest.xml", "base/assets/jiagu/local-payload.jgl")) {
                    out.putNextEntry(new java.util.jar.JarEntry(name));
                    out.write(name.endsWith(".jgl") ? new byte[]{1, 2, (byte)(scenario == 2 ? 4 : 3)} : new byte[0]); out.closeEntry();
                }
                if (scenario != 1) {
                    out.putNextEntry(new java.util.jar.JarEntry("base/dex/classes.dex"));
                    byte[] dex = new byte[112]; dex[0] = 'd'; dex[1] = 'e'; dex[2] = 'x'; out.write(dex); out.closeEntry();
                }
                if (scenario == 3) {
                    out.putNextEntry(new java.util.jar.JarEntry("feature/manifest/AndroidManifest.xml")); out.closeEntry();
                }
            }
            task.getBundleFile().set(bundle.toFile());
            if (scenario == 0) task.verify();
            else {
                try { task.verify(); org.junit.Assert.fail("Invalid bundle accepted: " + scenario); }
                catch (java.io.IOException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage().contains(scenario == 1 ? "Shell DEX missing" : scenario == 2 ? "differs from producer" : "Only base-module"));
                }
            }
        }
    }
}
