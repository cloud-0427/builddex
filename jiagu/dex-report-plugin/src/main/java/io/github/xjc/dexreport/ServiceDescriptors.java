package io.github.xjc.dexreport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.*;

/** ServiceLoader metadata is a dynamic code entry point, not an ordinary resource. */
final class ServiceDescriptors {
    static final String PREFIX = "META-INF/services/";
    final SortedMap<String, SortedSet<String>> entries = new TreeMap<>();

    void add(String path, byte[] content) throws IOException {
        String service = path.substring(PREFIX.length());
        validateName(service);
        SortedSet<String> providers = entries.computeIfAbsent(service, key -> new TreeSet<>());
        for (String line : new String(content, StandardCharsets.UTF_8).split("\\R")) {
            String provider = line.split("#", 2)[0].trim();
            if (!provider.isEmpty()) {
                validateName(provider);
                providers.add(provider);
            }
        }
    }

    private static void validateName(String name) throws IOException {
        if (!name.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*")) {
            throw new IOException("Invalid ServiceLoader class name: " + name);
        }
    }

    void write(JarOutputStream output) throws IOException {
        for (Map.Entry<String, SortedSet<String>> entry : entries.entrySet()) {
            output.putNextEntry(new JarEntry(PREFIX + entry.getKey()));
            output.write((String.join("\n", entry.getValue()) + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    static ServiceDescriptors read(Path path) throws IOException {
        ServiceDescriptors result = new ServiceDescriptors();
        try (JarFile jar = new JarFile(path.toFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (!entry.isDirectory() && entry.getName().startsWith(PREFIX)) {
                    try (java.io.InputStream input = jar.getInputStream(entry)) {
                        result.add(entry.getName(), input.readAllBytes());
                    }
                }
            }
        }
        return result;
    }

    List<String> keepRules() {
        List<String> rules = new ArrayList<>();
        for (Map.Entry<String, SortedSet<String>> entry : entries.entrySet()) {
            // Pin the SPI contract, but not all methods of each implementation.
            rules.add("-keep,allowaccessmodification class " + entry.getKey() + " { public <methods>; }");
            for (String provider : entry.getValue()) {
                rules.add("-keep,allowaccessmodification class " + provider + " { public <init>(); }");
            }
        }
        return rules;
    }
}
