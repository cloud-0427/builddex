package io.github.xjc.dexreport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Conservatively identifies class artifacts that have already been emitted by R8. */
final class R8ArtifactDetector {
    enum Classification {
        RAW,
        R8_PROCESSED,
        CONFLICTING_EVIDENCE
    }

    static final class Result {
        final Classification classification;
        final int classCount;
        final int markedClassCount;
        final String evidence;

        Result(Classification classification, int classCount, int markedClassCount, String evidence) {
            this.classification = classification;
            this.classCount = classCount;
            this.markedClassCount = markedClassCount;
            this.evidence = evidence;
        }
    }

    private static final byte[] R8_METADATA_PREFIX = ascii("~~R8{");
    private static final byte[] R8_MAP_ID = ascii("r8-map-id-");

    private R8ArtifactDetector() {}

    static Result inspectJar(Path jar) throws IOException {
        Map<String, Boolean> classes = new LinkedHashMap<>();
        try (JarFile input = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !isProgramClass(entry.getName())) {
                    continue;
                }
                try (InputStream stream = input.getInputStream(entry)) {
                    classes.put(entry.getName(), hasR8Marker(readAll(stream)));
                }
            }
        }
        return classify(classes);
    }

    static Result inspectDirectory(Path directory) throws IOException {
        Map<String, Boolean> classes = new LinkedHashMap<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile).sorted()::iterator) {
                String relative = directory.relativize(path).toString().replace('\\', '/');
                if (isProgramClass(relative)) {
                    classes.put(relative, hasR8Marker(Files.readAllBytes(path)));
                }
            }
        }
        return classify(classes);
    }

    private static Result classify(Map<String, Boolean> classes) {
        int marked = 0;
        String firstMarked = null;
        String firstUnmarked = null;
        for (Map.Entry<String, Boolean> entry : classes.entrySet()) {
            if (entry.getValue()) {
                marked++;
                if (firstMarked == null) firstMarked = entry.getKey();
            } else if (firstUnmarked == null) {
                firstUnmarked = entry.getKey();
            }
        }
        int total = classes.size();
        if (marked == 0) {
            return new Result(Classification.RAW, total, 0,
                    total == 0 ? "no class entries" : "no R8 class-file metadata");
        }
        if (marked == total) {
            return new Result(Classification.R8_PROCESSED, total, marked,
                    "R8 metadata on every class; sample=" + firstMarked);
        }
        return new Result(Classification.CONFLICTING_EVIDENCE, total, marked,
                "mixed R8/raw classes; marked=" + firstMarked + ", unmarked=" + firstUnmarked);
    }

    private static boolean hasR8Marker(byte[] classFile) {
        // Current R8 CF output writes both a structured ~~R8 metadata string and the
        // corresponding map id into the constant pool. Requiring both makes this a
        // high-confidence signal and avoids treating short/obfuscated names as proof.
        return contains(classFile, R8_METADATA_PREFIX) && contains(classFile, R8_MAP_ID);
    }

    private static boolean isProgramClass(String name) {
        return name.endsWith(".class")
                && !name.startsWith("META-INF/")
                && !name.equals("module-info.class");
    }

    private static boolean contains(byte[] bytes, byte[] needle) {
        outer:
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
