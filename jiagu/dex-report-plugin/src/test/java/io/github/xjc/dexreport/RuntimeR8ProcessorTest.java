package io.github.xjc.dexreport;

import org.junit.Test;
import org.junit.Assume;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.ArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuntimeR8ProcessorTest {
    @Test
    public void runtimeR8OutputContainsOnlyRuntimeProgramClasses() throws Exception {
        Path root = Files.createTempDirectory("runtime-r8-test");
        Path runtimeClasses = compile(root, "io.github.xjc.jiagu.Helper",
                "package io.github.xjc.jiagu; final class Helper { " +
                        "static int value() { return 7; } }");
        compileInto(runtimeClasses, "io.github.xjc.jiagu.Entry",
                "package io.github.xjc.jiagu; public class Entry { " +
                        "public static int entry() { return Helper.value(); } }");
        Path libraryClasses = compile(root, "com.vendor.sdk.LibraryType",
                "package com.vendor.sdk; public class LibraryType {}");
        Path runtimeJar = jar(root.resolve("runtime.jar"), runtimeClasses);
        Path libraryJar = jar(root.resolve("library.jar"), libraryClasses);
        Path rules = root.resolve("runtime.pro");
        Files.write(rules, java.util.Arrays.asList(
                "-keep class io.github.xjc.jiagu.Entry { public static int entry(); }",
                "-repackageclasses 'io.github.xjc.jiagu.r8'"), StandardCharsets.UTF_8);
        Path output = root.resolve("runtime-r8.jar");
        Path mapping = root.resolve("runtime-mapping.txt");
        Path androidJar = findAndroidJar();
        Assume.assumeTrue("Android SDK platform jar is not available", androidJar != null);
        java.util.List<Path> libraries = new ArrayList<>();
        libraries.add(libraryJar);
        libraries.add(androidJar);

        RuntimeR8Processor.run(runtimeJar, libraries,
                Collections.singletonList(rules), output, mapping);

        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertTrue(zip.getEntry("io/github/xjc/jiagu/Entry.class") != null);
            assertFalse("R8 must not emit classes supplied as library inputs",
                    zip.stream().anyMatch(entry -> entry.getName().startsWith("com/vendor/sdk/")));
            assertTrue("R8 should retain a runtime mapping", Files.size(mapping) > 0);
        }
    }

    private static Path compile(Path root, String className, String source) throws IOException {
        Path classes = Files.createDirectories(root.resolve("classes"));
        compileInto(classes, className, source);
        return classes;
    }

    private static void compileInto(Path output, String className, String source) throws IOException {
        Path sourceFile = output.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null || compiler.run(null, null, null, "-classpath", output.toString(),
                "-d", output.toString(),
                sourceFile.toString()) != 0) {
            throw new IOException("Could not compile test class " + className);
        }
        Files.delete(sourceFile);
    }

    private static Path jar(Path output, Path classes) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output));
             java.util.stream.Stream<Path> files = Files.walk(classes)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                String name = classes.relativize(path).toString().replace('\\', '/');
                try {
                    jar.putNextEntry(new JarEntry(name));
                    Files.copy(path, jar);
                    jar.closeEntry();
                } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
        } catch (java.io.UncheckedIOException error) {
            throw error.getCause();
        }
        return output;
    }

    private static Path findAndroidJar() throws IOException {
        java.util.List<Path> roots = new ArrayList<>();
        String sdkRoot = System.getenv("ANDROID_HOME");
        if (sdkRoot != null) roots.add(java.nio.file.Paths.get(sdkRoot));
        String sdkRootAlt = System.getenv("ANDROID_SDK_ROOT");
        if (sdkRootAlt != null) roots.add(java.nio.file.Paths.get(sdkRootAlt));
        Path localProperties = java.nio.file.Paths.get("..", "local.properties");
        if (!Files.isRegularFile(localProperties)) localProperties = java.nio.file.Paths.get("local.properties");
        if (Files.isRegularFile(localProperties)) {
            java.util.Properties properties = new java.util.Properties();
            try (java.io.InputStream input = Files.newInputStream(localProperties)) {
                properties.load(input);
            }
            String sdkDir = properties.getProperty("sdk.dir");
            if (sdkDir != null) roots.add(java.nio.file.Paths.get(sdkDir.replace("\\:", ":")));
        }
        for (Path root : roots) {
            Path direct = root.resolve("platforms");
            if (!Files.isDirectory(direct)) direct = root.resolve("sdk/platforms");
            if (Files.isDirectory(direct)) {
                try (java.util.stream.Stream<Path> platforms = Files.list(direct)) {
                    Path found = platforms.map(path -> path.resolve("android.jar"))
                            .filter(Files::isRegularFile)
                            .max(java.util.Comparator.comparing(Path::toString)).orElse(null);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }
}
