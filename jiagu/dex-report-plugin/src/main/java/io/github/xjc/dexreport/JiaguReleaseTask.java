package io.github.xjc.dexreport;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Creates/updates the server release only after final resources are available. */
@DisableCachingByDefault(because = "Creates server-side state and embeds release identity")
public abstract class JiaguReleaseTask extends DefaultTask {
    public JiaguReleaseTask() {
        // The server database is external state and may have been reset even
        // when all local inputs and outputs are unchanged.
        getOutputs().upToDateWhen(task -> false);
    }

    @Input public abstract Property<String> getPackageName();
    @Input public abstract Property<Integer> getVersionCode();
    @Input public abstract Property<String> getServerUrl();
    @Input public abstract Property<String> getCompanyId();
    @Internal public abstract Property<String> getCompanyApiKey();
    @Input public abstract Property<String> getCertificateSha256();
    @Input public abstract ListProperty<String> getCertificateSha256Digests();
    @Input public abstract Property<Boolean> getPublish();
    @Input public abstract Property<String> getBuildInvocationId();

    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPayloadFile();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBusinessDexSha256File();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getResourcePackage();
    @InputDirectory @Optional @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getMergedAssets();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getNativeInputs();
    @Internal public abstract DirectoryProperty getNdkDirectory();

    @OutputDirectory public abstract DirectoryProperty getOutJniLibsDir();
    @OutputFile public abstract RegularFileProperty getReleaseMetadataFile();

    @TaskAction
    public void createRelease() throws IOException {
        String serverUrl = getServerUrl().get().trim();
        String companyId = getCompanyId().get().trim();
        String companyApiKey = getCompanyApiKey().get().trim();
        if (serverUrl.isEmpty() || companyId.isEmpty() || companyApiKey.isEmpty()) {
            throw new IOException("serverUrl, companyId and companyApiKey are required");
        }

        File payload = getPayloadFile().get().getAsFile();
        String businessDexSha256 = new String(
                Files.readAllBytes(getBusinessDexSha256File().get().getAsFile().toPath()),
                StandardCharsets.UTF_8).trim();
        String resourcesSha256 = hashResourcePackage();
        String nativeLibsSha256 = hashNativeInputs();
        List<String> certificates = new ArrayList<>(getCertificateSha256Digests().get());
        certificates.add(getCertificateSha256().get());
        certificates = sortedUnique(certificates);

        getLogger().lifecycle("[Jiagu] 最终资源已生成，正在创建/更新 Release");
        JiaguServerClient client = new JiaguServerClient(serverUrl, companyId, companyApiKey);
        // Validate the key before sending the potentially large multipart body.
        // Otherwise an early HTTP 401 can race with the request upload and be
        // surfaced by HttpURLConnection only as a socket error.
        client.verifyCompanyAccess();
        JiaguServerClient.PublicConfig publicConfig = client.getPublicConfig();
        int versionCode = getVersionCode().get();
        JiaguServerClient.Release release = client.createRelease(
                payload, "app-main", versionCode, getPackageName().get(), versionCode,
                certificates, businessDexSha256, resourcesSha256, nativeLibsSha256);

        File runtimeConfig = File.createTempFile("jiagu_runtime_config", ".json");
        try {
            Files.write(runtimeConfig.toPath(), runtimeConfigJson(
                    serverUrl, companyId, publicConfig, release).getBytes(StandardCharsets.UTF_8));
            buildPayloadLibraries(runtimeConfig, getOutJniLibsDir().get().getAsFile());
        } finally {
            Files.deleteIfExists(runtimeConfig.toPath());
        }
        writeReleaseMetadata(release);
        getLogger().lifecycle(getPublish().get()
                ? "[Jiagu] Release {} 将在本次构建全部成功后发布"
                : "[Jiagu] Release {} 保持 DRAFT 状态", release.releaseId);
    }

    private String hashResourcePackage() throws IOException {
        File resourcePackage = getResourcePackage().get().getAsFile();
        if (!resourcePackage.isFile()) {
            throw new IOException("Final resource package is unavailable: " + resourcePackage);
        }
        List<EntryValue> entries = new ArrayList<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(resourcePackage)) {
            Enumeration<? extends java.util.zip.ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                java.util.zip.ZipEntry entry = all.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !(name.equals("AndroidManifest.xml")
                        || name.equals("resources.arsc") || name.startsWith("res/")
                        || name.startsWith("assets/"))) continue;
                try (InputStream input = zip.getInputStream(entry)) {
                    entries.add(new EntryValue(name, readStream(input)));
                }
            }
        }
        if (getMergedAssets().isPresent() && getMergedAssets().get().getAsFile().isDirectory()) {
            File root = getMergedAssets().get().getAsFile();
            try (java.util.stream.Stream<Path> paths = Files.walk(root.toPath())) {
                for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                    String name = "assets/" + root.toPath().relativize(path).toString().replace('\\', '/');
                    entries.add(new EntryValue(name, Files.readAllBytes(path)));
                }
            }
        }
        return hashEntryValues("JIAGU-RESOURCES-V1", entries);
    }

    private String hashNativeInputs() throws IOException {
        List<EntryValue> entries = new ArrayList<>();
        for (File file : getNativeInputs().getFiles()) {
            if (file.isDirectory()) {
                try (java.util.stream.Stream<Path> paths = Files.walk(file.toPath())) {
                    for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)
                            .filter(value -> value.getFileName().toString().endsWith(".so"))::iterator) {
                        if (!path.getFileName().toString().equals("liblog_ext.so")) {
                            entries.add(new EntryValue(nativePath(path.toFile()), strippedNativeBytes(path.toFile())));
                        }
                    }
                }
            } else if (file.isFile() && file.getName().endsWith(".so")
                    && !file.getName().equals("liblog_ext.so")) {
                entries.add(new EntryValue(nativePath(file), strippedNativeBytes(file)));
            } else if (file.isFile() && (file.getName().endsWith(".aar") || file.getName().endsWith(".zip"))) {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
                    Enumeration<? extends java.util.zip.ZipEntry> all = zip.entries();
                    while (all.hasMoreElements()) {
                        java.util.zip.ZipEntry entry = all.nextElement();
                        String name = entry.getName().replace('\\', '/');
                        if (entry.isDirectory() || !name.startsWith("jni/") || !name.endsWith(".so")
                                || name.endsWith("/liblog_ext.so")) continue;
                        try (InputStream input = zip.getInputStream(entry)) {
                            entries.add(new EntryValue(name.substring(4), readStream(input)));
                        }
                    }
                }
            }
        }
        return hashEntryValues("JIAGU-NATIVE-LIBS-V1", entries);
    }

    private byte[] strippedNativeBytes(File library) throws IOException {
        File temporary = File.createTempFile("jiagu_native_", ".so");
        try {
            Files.copy(library.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
            File strip = new File(findToolchainBin(resolveNdkDirectory()),
                    "llvm-strip" + (isWindows() ? ".exe" : ""));
            if (!strip.isFile()) throw new IOException("NDK llvm-strip is unavailable: " + strip);
            runCommand(Arrays.asList(strip.getAbsolutePath(), "--strip-unneeded", temporary.getAbsolutePath()),
                    temporary.getParentFile(), "normalize native library " + library.getName());
            return Files.readAllBytes(temporary.toPath());
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private void buildPayloadLibraries(File config, File outputRoot) throws IOException {
        File clang = new File(findToolchainBin(resolveNdkDirectory()), isWindows() ? "clang.exe" : "clang");
        if (!clang.isFile()) throw new IOException("NDK clang is unavailable: " + clang);
        String[][] targets = {
                {"armeabi-v7a", "armv7a-linux-androideabi29", "%progbits"},
                {"arm64-v8a", "aarch64-linux-android29", "%progbits"},
                {"x86", "i686-linux-android29", "@progbits"},
                {"x86_64", "x86_64-linux-android29", "@progbits"}
        };
        File workRoot = new File(getTemporaryDir(), "payload-elf");
        deleteDirectory(workRoot);
        if (!workRoot.mkdirs() && !workRoot.isDirectory()) throw new IOException("Cannot create " + workRoot);
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(targets.length,
                Math.max(1, Runtime.getRuntime().availableProcessors())));
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (String[] target : targets) futures.add(executor.submit(() -> {
                try { buildPayloadLibrary(config, outputRoot, workRoot, clang, target); }
                catch (IOException error) { throw new RuntimeException(error); }
            }));
            for (Future<?> future : futures) {
                try { future.get(); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while building payload ELF", error);
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof RuntimeException && cause.getCause() instanceof IOException) {
                        throw (IOException) cause.getCause();
                    }
                    throw new IOException("Failed to build payload ELF", cause);
                }
            }
        } finally {
            executor.shutdownNow();
            deleteDirectory(workRoot);
        }
    }

    private void buildPayloadLibrary(File config, File outputRoot, File workRoot,
                                     File clang, String[] target) throws IOException {
        File workDir = new File(workRoot, target[0]);
        if (!workDir.mkdirs() && !workDir.isDirectory()) throw new IOException("Cannot create " + workDir);
        File wrapper = new File(workDir, "payload_wrapper.c");
        try (InputStream input = JiaguReleaseTask.class.getResourceAsStream("/elf-wrapper/payload_wrapper.c")) {
            if (input == null) throw new IOException("Missing plugin resource: elf-wrapper/payload_wrapper.c");
            Files.copy(input, wrapper.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        String configPath = config.getAbsolutePath().replace("\\", "/").replace("\"", "\\\"");
        File assembly = new File(workDir, "payload.S");
        Files.write(assembly.toPath(), (".section .jg_payload,\"a\"," + target[2] + "\n"
                + ".balign 16\n.global jiagu_payload_start\n.hidden jiagu_payload_start\n"
                + "jiagu_payload_start:\n.incbin \"" + configPath + "\"\n"
                + ".global jiagu_payload_end\n.hidden jiagu_payload_end\njiagu_payload_end:\n")
                .getBytes(StandardCharsets.UTF_8));
        File abiDir = new File(outputRoot, target[0]);
        if (!abiDir.mkdirs() && !abiDir.isDirectory()) throw new IOException("Cannot create " + abiDir);
        File output = new File(abiDir, "liblog_ext.so");
        runCommand(Arrays.asList(clang.getAbsolutePath(), "--target=" + target[1], "-fPIC",
                "-fvisibility=hidden", "-shared", "-nostdlib", wrapper.getAbsolutePath(),
                assembly.getAbsolutePath(), "-Wl,-soname,liblog_ext.so", "-Wl,--build-id=none",
                "-Wl,--no-gc-sections", "-Wl,-z,max-page-size=16384", "-o", output.getAbsolutePath()),
                workDir, "build payload ELF for " + target[0]);
        verifyElf(output);
    }

    private File resolveNdkDirectory() throws IOException {
        try {
            File ndk = getNdkDirectory().get().getAsFile();
            if (!ndk.isDirectory()) throw new IOException("Android NDK directory is unavailable: " + ndk);
            return ndk;
        } catch (Exception error) {
            throw new IOException("[Jiagu] Android NDK 配置不可用，无法生成 RuntimeConfig ELF", error);
        }
    }

    private File findToolchainBin(File ndk) throws IOException {
        File root = new File(ndk, "toolchains/llvm/prebuilt");
        File[] candidates = root.listFiles(File::isDirectory);
        if (candidates == null || candidates.length == 0) throw new IOException("LLVM toolchain unavailable: " + root);
        Arrays.sort(candidates, Comparator.comparing(File::getName));
        String prefix = isWindows() ? "windows-" : (System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("mac") ? "darwin-" : "linux-");
        for (File candidate : candidates) if (candidate.getName().startsWith(prefix)) return new File(candidate, "bin");
        return new File(candidates[0], "bin");
    }

    private void runCommand(List<String> command, File directory, String description) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory).redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(readStream(input), StandardCharsets.UTF_8);
        }
        try {
            int code = process.waitFor();
            if (code != 0) throw new IOException("Failed to " + description + " (exit " + code + "):\n" + output);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while trying to " + description, error);
        }
        if (!output.trim().isEmpty()) getLogger().info("[Jiagu] {} output:\n{}", description, output.trim());
    }

    private void verifyElf(File file) throws IOException {
        byte[] header = Files.readAllBytes(file.toPath());
        if (header.length < 4 || header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
            throw new IOException("Generated payload library is not a valid ELF: " + file);
        }
    }

    private String hashEntryValues(String domain, List<EntryValue> entries) throws IOException {
        TreeMap<String, byte[]> unique = new TreeMap<>();
        for (EntryValue entry : entries) {
            byte[] previous = unique.putIfAbsent(entry.path, entry.data);
            if (previous != null && !Arrays.equals(previous, entry.data)) {
                throw new IOException("Conflicting final build entries share path " + entry.path);
            }
        }
        List<String> values = new ArrayList<>();
        values.add(domain);
        for (Map.Entry<String, byte[]> entry : unique.entrySet()) {
            values.add(entry.getKey());
            values.add(Integer.toString(entry.getValue().length));
            values.add(JiaguServerClient.sha256(entry.getValue()));
        }
        return JiaguServerClient.sha256(canonical(values.toArray(new String[0])).getBytes(StandardCharsets.UTF_8));
    }

    private String runtimeConfigJson(String serverUrl, String companyId,
                                     JiaguServerClient.PublicConfig config, JiaguServerClient.Release release) {
        return "{" +
                "\"configVersion\":2," +
                "\"serverUrl\":" + JiaguServerClient.json(serverUrl) + "," +
                "\"companyId\":" + JiaguServerClient.json(companyId) + "," +
                "\"releaseId\":" + JiaguServerClient.json(release.releaseId) + "," +
                "\"payloadId\":" + JiaguServerClient.json(release.payloadId) + "," +
                "\"payloadVersion\":" + release.payloadVersion + "," +
                "\"packageName\":" + JiaguServerClient.json(release.packageName) + "," +
                "\"versionCode\":" + release.versionCode + "," +
                "\"certificateSha256Digests\":" + jsonArray(release.certificateSha256Digests) + "," +
                "\"certificateSetSha256\":" + JiaguServerClient.json(release.certificateSetSha256) + "," +
                "\"businessDexSha256\":" + JiaguServerClient.json(release.businessDexSha256) + "," +
                "\"resourcesSha256\":" + JiaguServerClient.json(release.resourcesSha256) + "," +
                "\"nativeLibsSha256\":" + JiaguServerClient.json(release.nativeLibsSha256) + "," +
                "\"releaseBuildSha256\":" + JiaguServerClient.json(release.releaseBuildSha256) + "," +
                "\"payloadPlaintextSha256\":" + JiaguServerClient.json(release.plaintextSha256) + "," +
                "\"payloadKeyVersion\":" + release.payloadKeyVersion + "," +
                "\"serverKeyId\":" + JiaguServerClient.json(config.serverKeyId) + "," +
                "\"serverPublicKey\":" + JiaguServerClient.json(config.serverPublicKey) + "," +
                "\"wrapAlgorithm\":\"RSA-OAEP-SHA1\"," +
                "\"integrityMode\":" + JiaguServerClient.json(config.integrityMode) + "," +
                "\"integrityCloudProjectNumber\":" + config.integrityCloudProjectNumber + "}";
    }

    private void writeReleaseMetadata(JiaguServerClient.Release release) throws IOException {
        File target = getReleaseMetadataFile().get().getAsFile();
        Files.createDirectories(target.toPath().getParent());
        Files.write(target.toPath(), ("{\"releaseId\":" + JiaguServerClient.json(release.releaseId)
                + ",\"status\":" + JiaguServerClient.json(release.status)
                + ",\"packageName\":" + JiaguServerClient.json(release.packageName)
                + ",\"versionCode\":" + release.versionCode
                + ",\"buildInvocationId\":" + JiaguServerClient.json(getBuildInvocationId().get()) + "}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String nativePath(File file) {
        String value = file.getPath().replace('\\', '/');
        for (String abi : Arrays.asList("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
            if (value.contains("/" + abi + "/")) return abi + "/" + file.getName();
        }
        return "unknown/" + file.getName();
    }

    private static String canonical(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) result.append(value.getBytes(StandardCharsets.UTF_8).length)
                .append(':').append(value).append('\n');
        return result.toString();
    }

    private static List<String> sortedUnique(List<String> values) {
        TreeSet<String> unique = new TreeSet<>();
        for (String value : values) if (value != null && !value.trim().isEmpty()) unique.add(value.trim());
        return new ArrayList<>(unique);
    }

    private static String jsonArray(List<String> values) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) result.append(',');
            result.append(JiaguServerClient.json(values.get(i)));
        }
        return result.append(']').toString();
    }

    private static byte[] readStream(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static void deleteDirectory(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteDirectory(child);
        file.delete();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static final class EntryValue {
        final String path;
        final byte[] data;
        EntryValue(String path, byte[] data) { this.path = path; this.data = data; }
    }
}
