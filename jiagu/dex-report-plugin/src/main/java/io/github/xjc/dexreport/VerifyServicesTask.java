package io.github.xjc.dexreport;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.InflaterInputStream;

/** Checks the packaged descriptors against the actual shell and payload DEX definitions. */
@DisableCachingByDefault(because = "Verification has no output and must inspect the packaged APK")
public abstract class VerifyServicesTask extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getApkDirectory();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getDescriptors();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPayload();

    @TaskAction public void verify() throws IOException {
        ServiceDescriptors expected = ServiceDescriptors.read(getDescriptors().get().getAsFile().toPath());
        Map<String, String> businessDefinitions = new LinkedHashMap<>();
        byte[] payload = Files.readAllBytes(getPayload().get().getAsFile().toPath());
        ByteBuffer header = ByteBuffer.wrap(payload);
        if (header.getInt() != 0x4a473300) throw new IOException("Invalid JG3 payload");
        int count = header.getInt();
        int body = 8 + count * 12;
        for (int i = 0; i < count; i++) {
            int offset = header.getInt(), length = header.getInt(), rawLength = header.getInt();
            try (InputStream input = new InflaterInputStream(
                    new ByteArrayInputStream(payload, body + offset, length))) {
                byte[] dex = input.readAllBytes();
                if (dex.length != rawLength) throw new IOException("Invalid payload DEX length");
                addDefinitions(businessDefinitions, classNames(dex), "payload dex #" + (i + 1));
            }
        }
        List<Path> apks = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.walk(getApkDirectory().get().getAsFile().toPath())) {
            files.filter(path -> path.toString().endsWith(".apk")).forEach(apks::add);
        }
        if (apks.isEmpty()) throw new IOException("No APK found for service verification");
        for (Path apk : apks) {
            ServiceDescriptors actual = ServiceDescriptors.read(apk);
            if (!expected.entries.equals(actual.entries)) {
                throw new IOException("APK ServiceLoader descriptors differ from input: " + apk
                        + " expected=" + expected.entries + " actual=" + actual.entries);
            }
            Map<String, String> shellDefinitions = new LinkedHashMap<>();
            try (JarFile jar = new JarFile(apk.toFile())) {
                Set<String> seen = new HashSet<>();
                for (JarEntry entry : Collections.list(jar.entries())) {
                    String name = entry.getName();
                    if (name.startsWith(ServiceDescriptors.PREFIX) && !seen.add(name)) {
                        throw new IOException("Duplicate service descriptor: " + name);
                    }
                    if (name.matches("classes[0-9]*\\.dex")) {
                        try (InputStream input = jar.getInputStream(entry)) {
                            addDefinitions(shellDefinitions, classNames(input.readAllBytes()),
                                    apk + "!/" + name);
                        }
                    }
                }
            }
            SortedSet<String> collisions = duplicateClassNames(
                    businessDefinitions.keySet(), shellDefinitions.keySet());
            if (!collisions.isEmpty()) {
                throw new IOException("Shell/Payload duplicate class definitions in " + apk + ": "
                        + collisions + ". These descriptors would be resolved by the wrong ClassLoader.");
            }
            Set<String> classes = new HashSet<>(businessDefinitions.keySet());
            classes.addAll(shellDefinitions.keySet());
            for (Map.Entry<String, SortedSet<String>> entry : expected.entries.entrySet()) {
                Set<String> required = new HashSet<>(entry.getValue());
                required.add(entry.getKey());
                required.removeAll(classes);
                if (!required.isEmpty()) throw new IOException("Service classes missing/renamed in DEX: " + required);
            }
            getLogger().lifecycle("[Jiagu] APK 服务声明与壳/业务 DEX 校验通过: {} ({} services)", apk, expected.entries.size());
        }
    }

    private static void addDefinitions(Map<String, String> definitions, Set<String> classes,
                                       String origin) throws IOException {
        for (String className : classes) {
            String previous = definitions.putIfAbsent(className, origin);
            if (previous != null) {
                throw new IOException("Duplicate class definition: " + className
                        + " in " + previous + " and " + origin);
            }
        }
    }

    static SortedSet<String> duplicateClassNames(Collection<String> first,
                                                  Collection<String> second) {
        SortedSet<String> duplicates = new TreeSet<>(first);
        duplicates.retainAll(new HashSet<>(second));
        return duplicates;
    }

    static Set<String> classNames(byte[] dex) throws IOException {
        if (dex.length < 112 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x') {
            throw new IOException("Invalid DEX header");
        }
        ByteBuffer data = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
        int strings = data.getInt(60), types = data.getInt(68);
        int count = data.getInt(96), definitions = data.getInt(100);
        Set<String> result = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int type = data.getInt(definitions + 32 * i);
            int string = data.getInt(types + 4 * type);
            int start = data.getInt(strings + 4 * string);
            while ((dex[start++] & 0x80) != 0) { /* UTF-16 length, ULEB128 */ }
            int end = start;
            while (dex[end] != 0) end++;
            String descriptor = new String(dex, start, end - start, StandardCharsets.UTF_8);
            result.add(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
        }
        return result;
    }
}
