package io.github.xjc.dexreport;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;

import org.gradle.api.tasks.Internal;
import java.nio.charset.StandardCharsets;

/**
 * 核心加固打包任务：
 * 负责遍历所有的 Class 文件，将壳代码放入输出 Jar，将业务代码加密。
 */
public abstract class JiaguTask extends DefaultTask {

    @Input
    public abstract Property<String> getPackageName();

    @Input
    public abstract Property<String> getVersionName();

    @Input
    public abstract Property<Integer> getVersionCode();

    @Input
    public abstract Property<String> getServerUrl();

    @Input
    public abstract Property<String> getCompanyId();

    @Internal
    public abstract Property<String> getCompanyApiKey();

    @Input
    public abstract Property<String> getCertificateSha256();

    @Input
    public abstract Property<Boolean> getPublish();

    @InputFiles
    public abstract ListProperty<RegularFile> getAllJars();

    @InputFiles
    public abstract ListProperty<Directory> getAllDirectories();

    @Internal
    public abstract DirectoryProperty getNdkDirectory();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @OutputDirectory
    public abstract DirectoryProperty getOutJniLibsDir();

    @TaskAction
    public void execute() throws IOException {
        long taskStartedAt = System.nanoTime();
        Map<String, Long> stageTimes = new LinkedHashMap<>();
        getLogger().lifecycle("[Jiagu][计时] 加固任务开始: {}", getPath());

        long stageStartedAt = System.nanoTime();
        int versionCode = getVersionCode().get();
        String companyId = getCompanyId().get().trim();
        String companyApiKey = getCompanyApiKey().get().trim();
        String serverUrl = getServerUrl().get().trim();
        if (companyId.isEmpty() || companyApiKey.isEmpty() || serverUrl.isEmpty()) {
            throw new IOException("serverUrl, companyId and companyApiKey are required");
        }
        getLogger().lifecycle("[Jiagu] 服务端={}, 公司={}, Key 指纹={}", serverUrl, companyId,
                shortFingerprint(companyApiKey));

        File outputJarFile = getOutputJar().get().getAsFile();
        File jniLibsDir = getOutJniLibsDir().get().getAsFile();

        // ... 省略部分中间 JAR 处理逻辑 (与之前相同) ...

        Set<String> processedNames = new HashSet<>();
        File tempBusinessJar = File.createTempFile("business", ".jar");
        tempBusinessJar.deleteOnExit();

        getLogger().lifecycle("[Jiagu] 正在执行全量代码扫描与分离...");
        stageStartedAt = System.nanoTime();

        try (JarOutputStream shellJos = new JarOutputStream(new FileOutputStream(outputJarFile));
             JarOutputStream businessJos = new JarOutputStream(new FileOutputStream(tempBusinessJar))) {
            // 中间业务 JAR 只供紧随其后的 D8 使用，无需耗时做 ZIP 压缩。
            // 壳 JAR 使用快速压缩，在不明显增大最终产物的前提下降低扫描阶段 CPU 开销。
            shellJos.setLevel(Deflater.BEST_SPEED);
            businessJos.setLevel(Deflater.NO_COMPRESSION);

            // 1. 处理所有输入的 JAR 文件（包括依赖库）
            long inputStartedAt = System.nanoTime();
            for (RegularFile jarFile : getAllJars().get()) {
                try (JarFile inputJar = new JarFile(jarFile.getAsFile())) {
                    Enumeration<JarEntry> entries = inputJar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;

                        try (InputStream is = inputJar.getInputStream(entry)) {
                            byte[] data = readStream(is);
                            processEntry(shellJos, businessJos, entry.getName(), data, processedNames);
                        }
                    }
                }
            }
            finishStage("依赖 JAR 扫描与分离", inputStartedAt, stageTimes);

            // 2. 处理所有目录（当前项目的编译产物）
            inputStartedAt = System.nanoTime();
            for (Directory dir : getAllDirectories().get()) {
                File dirFile = dir.getAsFile();
                try (java.util.stream.Stream<Path> paths = Files.walk(dirFile.toPath())) {
                    paths.filter(Files::isRegularFile)
                            .forEach(path -> {
                                String relativePath = dirFile.toPath().relativize(path).toString().replace('\\', '/');
                                try {
                                    byte[] data = Files.readAllBytes(path);
                                    processEntry(shellJos, businessJos, relativePath, data, processedNames);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                }
            }
            finishStage("目录扫描与分离", inputStartedAt, stageTimes);
        }
        getLogger().lifecycle("[Jiagu][计时] 代码扫描与分离总耗时 {}",
                formatDuration(elapsedMillis(stageStartedAt)));

        // 3. 将业务代码 JAR 转换为 DEX
        getLogger().lifecycle("[Jiagu] 正在将业务代码转换为 DEX...");
        Path tempDexDir = Files.createTempDirectory("jiagu_dex");
        try {
            stageStartedAt = System.nanoTime();
            D8Command command = D8Command.builder()
                    .addProgramFiles(tempBusinessJar.toPath())
                    .setOutput(tempDexDir, OutputMode.DexIndexed)
                    .setMinApiLevel(29)
                    .build();
            D8.run(command);
            finishStage("D8 转换", stageStartedAt, stageTimes);

            File[] dexFiles = tempDexDir.toFile().listFiles((dir, name) -> name.endsWith(".dex"));
            if (dexFiles != null && dexFiles.length > 0) {
                // 按照文件名排序，确保 classes.dex, classes2.dex 等顺序一致
                Arrays.sort(dexFiles, Comparator.comparing(File::getName));
                
                // 生成服务端标准明文 JG3 容器。服务端会使用随机 Canonical Key 加密保存，
                // 设备下载时再转换为设备专属 JGPD 密文。
                File tempPayload = File.createTempFile("jiagu_payload", ".bin");
                long rawDexBytes = 0;
                long compressedDexBytes = 0;
                long uncompressedPayloadBytes = 0;
                stageStartedAt = System.nanoTime();
                try (FileOutputStream fos = new FileOutputStream(tempPayload)) {
                    fos.write("JG3\0".getBytes(StandardCharsets.UTF_8));
                    fos.write(intToBytes(dexFiles.length));

                    java.util.List<byte[]> compressedEntries = new java.util.ArrayList<>();
                    long currentOffset = 0;
                    for (File dexFile : dexFiles) {
                        byte[] dexData = Files.readAllBytes(dexFile.toPath());
                        byte[] compressedDex = compress(dexData);
                        rawDexBytes += dexData.length;
                        compressedDexBytes += compressedDex.length;
                        compressedEntries.add(compressedDex);
                        fos.write(intToBytes((int) currentOffset));
                        fos.write(intToBytes(compressedDex.length));
                        fos.write(intToBytes(dexData.length));
                        currentOffset += compressedDex.length;
                        uncompressedPayloadBytes += dexData.length;
                    }
                    for (byte[] entry : compressedEntries) {
                        fos.write(entry);
                    }
                }
                finishStage("DEX 压缩与 JG3 封装", stageStartedAt, stageTimes);
                logCompression("DEX 数据", rawDexBytes, compressedDexBytes);
                logCompression("JG3 Payload", uncompressedPayloadBytes, tempPayload.length());

                try {
                    stageStartedAt = System.nanoTime();
                    JiaguServerClient client = new JiaguServerClient(serverUrl, companyId, companyApiKey);
                    JiaguServerClient.PublicConfig publicConfig = client.getPublicConfig();
                    JiaguServerClient.Release release = client.createRelease(
                            tempPayload, "app-main", versionCode, getPackageName().get(), versionCode,
                            getCertificateSha256().get());
                    File runtimeConfig = File.createTempFile("jiagu_runtime_config", ".json");
                    try {
                        Files.write(runtimeConfig.toPath(), runtimeConfigJson(
                                serverUrl, companyId, publicConfig, release).getBytes(StandardCharsets.UTF_8));
                        buildPayloadLibraries(runtimeConfig, jniLibsDir, runtimeConfig.length());
                    } finally {
                        Files.deleteIfExists(runtimeConfig.toPath());
                    }
                    if (getPublish().get()) {
                        client.publish(release.releaseId);
                        getLogger().lifecycle("[Jiagu] release 已发布: {}", release.releaseId);
                    } else {
                        getLogger().lifecycle("[Jiagu] release 保持 DRAFT 状态 (未启用自动发布)");
                    }
                    finishStage("服务端创建发布与 RuntimeConfig ELF", stageStartedAt, stageTimes);
                } finally {
                    Files.deleteIfExists(tempPayload.toPath());
                }

                getLogger().lifecycle("[Jiagu] RuntimeConfig ELF 构建成功: liblog_ext.so 已生成至四个 ABI 目录");
            } else {
                throw new IOException("D8 failed to produce any DEX files");
            }
        } catch (NdkConfigurationException e) {
            getLogger().error(e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            String message = "[Jiagu] DEX 转换、服务端发布或 RuntimeConfig ELF 构建失败";
            getLogger().error(message, e);
            throw new IOException(message, e);
        } finally {
            // 清理临时文件
            deleteDirectory(tempDexDir.toFile());
            tempBusinessJar.delete();
        }
        
        getLogger().lifecycle("[Jiagu] 任务完成。输出: {}, 加密包目录: {}",
                outputJarFile.getName(), jniLibsDir.getName());
        long totalMs = elapsedMillis(taskStartedAt);
        getLogger().lifecycle("[Jiagu][计时] ===== 加固阶段耗时汇总 =====");
        for (Map.Entry<String, Long> entry : stageTimes.entrySet()) {
            getLogger().lifecycle("[Jiagu][计时] {}: {}", entry.getKey(), formatDuration(entry.getValue()));
        }
        getLogger().lifecycle("[Jiagu][计时] 总耗时: {}", formatDuration(totalMs));
    }

    private void processEntry(JarOutputStream shellJos, JarOutputStream businessJos, String name, byte[] data, Set<String> processedNames) throws IOException {
        if (processedNames.contains(name)) {
            return;
        }

        // 适度回调：保留 R 类在壳中。
        // 完全移除 R 类可能导致某些系统资源（如图标、主题）在壳 Application 阶段解析失败。
        boolean shouldKeepInShell = name.startsWith("io/github/xjc/jiagu/") || 
                                   name.contains("/R$") || name.endsWith("/R.class");

        if (shouldKeepInShell || !name.endsWith(".class")) {
            // 壳程序代码、白名单代码 或 非代码资源：透传到输出 JAR (壳 JAR)
            JarEntry outEntry = new JarEntry(name);
            shellJos.putNextEntry(outEntry);
            shellJos.write(data);
            shellJos.closeEntry();
        } else {
            // 业务 Class 文件：放入业务 JAR，后续统一转换 DEX 并加密
            JarEntry outEntry = new JarEntry(name);
            businessJos.putNextEntry(outEntry);
            businessJos.write(data);
            businessJos.closeEntry();
        }
        processedNames.add(name);
    }

    private void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteDirectory(file);
            }
        }
        directory.delete();
    }

    private void buildPayloadLibraries(File payloadFile, File jniLibsDir,
                                       long uncompressedPayloadBytes) throws IOException {
        File toolchainBin = findToolchainBin(resolveNdkDirectory());
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        File clang = new File(toolchainBin, windows ? "clang.exe" : "clang");
        if (!clang.isFile()) {
            throw ndkConfigurationException(
                    "在已选择的 NDK 中找不到 Clang: " + clang.getAbsolutePath(), null);
        }

        String[][] abiTargets = {
                {"armeabi-v7a", "armv7a-linux-androideabi29", "%progbits"},
                {"arm64-v8a", "aarch64-linux-android29", "%progbits"},
                {"x86", "i686-linux-android29", "@progbits"},
                {"x86_64", "x86_64-linux-android29", "@progbits"}
        };

        File workRoot = new File(getTemporaryDir(), "payload-elf");
        deleteDirectory(workRoot);
        if (!workRoot.mkdirs() && !workRoot.isDirectory()) {
            throw new IOException("Failed to create ELF work directory: " + workRoot);
        }

        int workerCount = Math.min(abiTargets.length,
                Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        List<Future<?>> futures = new ArrayList<>();
        getLogger().lifecycle("[Jiagu] 使用 {} 个并行进程链接 {} 个 ABI", workerCount, abiTargets.length);
        try {
            for (String[] abiTarget : abiTargets) {
                futures.add(executor.submit(() -> {
                    try {
                        buildPayloadLibrary(payloadFile, jniLibsDir, workRoot, clang,
                                abiTarget, uncompressedPayloadBytes);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while building payload ELF files", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException && cause.getCause() instanceof IOException) {
                        throw (IOException) cause.getCause();
                    }
                    throw new IOException("Failed to build payload ELF files", cause);
                }
            }
        } finally {
            executor.shutdownNow();
            deleteDirectory(workRoot);
        }
    }

    private void buildPayloadLibrary(File payloadFile, File jniLibsDir, File workRoot,
                                     File clang, String[] abiTarget,
                                     long uncompressedPayloadBytes) throws IOException {
                long startedAt = System.nanoTime();
                String abi = abiTarget[0];
                File abiWorkDir = new File(workRoot, abi);
                if (!abiWorkDir.mkdirs() && !abiWorkDir.isDirectory()) {
                    throw new IOException("Failed to create ABI work directory: " + abiWorkDir);
                }

                File wrapperSource = new File(abiWorkDir, "payload_wrapper.c");
                try (InputStream wrapper = JiaguTask.class.getResourceAsStream("/elf-wrapper/payload_wrapper.c")) {
                    if (wrapper == null) {
                        throw new IOException("Missing plugin resource: elf-wrapper/payload_wrapper.c");
                    }
                    Files.copy(wrapper, wrapperSource.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }

                File assemblySource = new File(abiWorkDir, "payload.S");
                String payloadPath = payloadFile.getAbsolutePath()
                        .replace("\\", "/")
                        .replace("\"", "\\\"");
                String assembly =
                        ".section .jg_payload,\"a\"," + abiTarget[2] + "\n" +
                        ".balign 16\n" +
                        ".global jiagu_payload_start\n" +
                        ".hidden jiagu_payload_start\n" +
                        "jiagu_payload_start:\n" +
                        ".incbin \"" + payloadPath + "\"\n" +
                        ".global jiagu_payload_end\n" +
                        ".hidden jiagu_payload_end\n" +
                        "jiagu_payload_end:\n";
                Files.write(assemblySource.toPath(), assembly.getBytes(StandardCharsets.UTF_8));

                File abiDir = new File(jniLibsDir, abi);
                if (!abiDir.mkdirs() && !abiDir.isDirectory()) {
                    throw new IOException("Failed to create JNI output directory: " + abiDir);
                }
                File outputSo = new File(abiDir, "liblog_ext.so");

                java.util.List<String> command = new java.util.ArrayList<>();
                command.add(clang.getAbsolutePath());
                command.add("--target=" + abiTarget[1]);
                command.add("-fPIC");
                command.add("-fvisibility=hidden");
                command.add("-shared");
                command.add("-nostdlib");
                command.add(wrapperSource.getAbsolutePath());
                command.add(assemblySource.getAbsolutePath());
                command.add("-Wl,-soname,liblog_ext.so");
                command.add("-Wl,--build-id=none");
                command.add("-Wl,--no-gc-sections");
                command.add("-Wl,-z,max-page-size=16384");
                command.add("-o");
                command.add(outputSo.getAbsolutePath());

                runCommand(command, abiWorkDir, "build payload ELF for " + abi);
                verifyElfHeader(outputSo);
                long elfOverhead = outputSo.length() - payloadFile.length();
                long estimatedBefore = uncompressedPayloadBytes + Math.max(0L, elfOverhead);
                getLogger().lifecycle(
                        "[Jiagu][压缩] liblog_ext.so {}: 压缩前约 {} -> 压缩后 {}，减少 {} ({})；链接耗时 {}",
                        abi, formatBytes(estimatedBefore), formatBytes(outputSo.length()),
                        formatBytes(Math.max(0L, estimatedBefore - outputSo.length())),
                        formatPercent(estimatedBefore, outputSo.length()),
                        formatDuration(elapsedMillis(startedAt)));
    }

    private byte[] compress(byte[] input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(32, input.length / 2));
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(output, deflater, 64 * 1024)) {
            compressed.write(input);
        } finally {
            deflater.end();
        }
        return output.toByteArray();
    }

    private void finishStage(String name, long startedAt, Map<String, Long> stageTimes) {
        long elapsedMs = elapsedMillis(startedAt);
        stageTimes.put(name, elapsedMs);
        getLogger().lifecycle("[Jiagu][计时] {} 完成，耗时 {}", name, formatDuration(elapsedMs));
    }

    private void logCompression(String name, long before, long after) {
        getLogger().lifecycle("[Jiagu][压缩] {}: {} -> {}，减少 {} ({})",
                name, formatBytes(before), formatBytes(after),
                formatBytes(Math.max(0L, before - after)), formatPercent(before, after));
    }

    private static long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static String formatDuration(long millis) {
        return String.format(java.util.Locale.ROOT, "%.3f s", millis / 1000.0d);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.2f KiB", bytes / 1024.0d);
        }
        return String.format(java.util.Locale.ROOT, "%.2f MiB", bytes / (1024.0d * 1024.0d));
    }

    private static String formatPercent(long before, long after) {
        if (before <= 0L) return "0.0%";
        double saved = Math.max(0.0d, (before - after) * 100.0d / before);
        return String.format(java.util.Locale.ROOT, "%.1f%%", saved);
    }

    private File findToolchainBin(File ndkDirectory) throws IOException {
        File prebuiltRoot = new File(ndkDirectory, "toolchains/llvm/prebuilt");
        File[] candidates = prebuiltRoot.listFiles(File::isDirectory);
        if (candidates == null || candidates.length == 0) {
            throw ndkConfigurationException(
                    "在已选择的 NDK 中找不到 LLVM toolchain: " + prebuiltRoot, null);
        }

        String osName = System.getProperty("os.name", "").toLowerCase();
        String preferredPrefix = osName.contains("win") ? "windows-" :
                (osName.contains("mac") ? "darwin-" : "linux-");
        for (File candidate : candidates) {
            if (candidate.getName().startsWith(preferredPrefix)) {
                return new File(candidate, "bin");
            }
        }
        return new File(candidates[0], "bin");
    }

    private File resolveNdkDirectory() throws NdkConfigurationException {
        final File ndkDirectory;
        try {
            ndkDirectory = getNdkDirectory().get().getAsFile();
        } catch (Exception e) {
            throw ndkConfigurationException(deepestCauseMessage(e), e);
        }

        if (!ndkDirectory.isDirectory()) {
            throw ndkConfigurationException(
                    "AGP 选择的 NDK 目录不存在: " + ndkDirectory.getAbsolutePath(), null);
        }
        return ndkDirectory;
    }

    private NdkConfigurationException ndkConfigurationException(String detail, Throwable cause) {
        StringBuilder message = new StringBuilder()
                .append("[Jiagu] Android NDK 配置不可用，无法生成加密载荷 ELF。\n")
                .append("即使消费工程没有本地 C/C++ 代码，Jiagu 也需要 NDK 来生成应用专属的 liblog_ext.so。\n")
                .append("请在 Android SDK Manager 中安装 NDK (Side by side)，并在应用模块固定一个已安装版本：\n")
                .append("  Groovy: android { ndkVersion '已安装的版本号' }\n")
                .append("  Kotlin: android { ndkVersion = \"已安装的版本号\" }\n")
                .append("如果已经安装，请确认 local.properties 中的 sdk.dir 指向安装该 NDK 的 Android SDK。");
        if (detail != null && !detail.trim().isEmpty()) {
            message.append("\n底层原因: ").append(detail.trim());
        }
        return new NdkConfigurationException(message.toString(), cause);
    }

    private String deepestCauseMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName()
                : message;
    }

    private static final class NdkConfigurationException extends IOException {
        private NdkConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void runCommand(java.util.List<String> command, File workingDirectory, String description) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(readStream(input), StandardCharsets.UTF_8);
        }

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Failed to " + description + " (exit " + exitCode + "):\n" + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while trying to " + description, e);
        }

        if (!output.trim().isEmpty()) {
            getLogger().info("[Jiagu] {} output:\n{}", description, output.trim());
        }
    }

    private void verifyElfHeader(File elfFile) throws IOException {
        byte[] header = new byte[4];
        try (InputStream input = Files.newInputStream(elfFile.toPath())) {
            int read = input.read(header);
            if (read != header.length ||
                    header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
                throw new IOException("Generated payload library is not a valid ELF: " + elfFile);
            }
        }
    }

    private String runtimeConfigJson(String serverUrl, String companyId,
                                     JiaguServerClient.PublicConfig publicConfig,
                                     JiaguServerClient.Release release) {
        return "{" +
                "\"configVersion\":1," +
                "\"serverUrl\":" + JiaguServerClient.json(serverUrl) + "," +
                "\"companyId\":" + JiaguServerClient.json(companyId) + "," +
                "\"releaseId\":" + JiaguServerClient.json(release.releaseId) + "," +
                "\"payloadId\":" + JiaguServerClient.json(release.payloadId) + "," +
                "\"payloadVersion\":" + release.payloadVersion + "," +
                "\"packageName\":" + JiaguServerClient.json(release.packageName) + "," +
                "\"versionCode\":" + release.versionCode + "," +
                "\"certificateSha256\":" + JiaguServerClient.json(release.certificateSha256) + "," +
                "\"payloadPlaintextSha256\":" + JiaguServerClient.json(release.plaintextSha256) + "," +
                "\"payloadKeyVersion\":" + release.payloadKeyVersion + "," +
                "\"serverKeyId\":" + JiaguServerClient.json(publicConfig.serverKeyId) + "," +
                "\"serverPublicKey\":" + JiaguServerClient.json(publicConfig.serverPublicKey) + "," +
                "\"integrityMode\":" + JiaguServerClient.json(publicConfig.integrityMode) + "," +
                "\"integrityCloudProjectNumber\":" + publicConfig.integrityCloudProjectNumber +
                "}";
    }

    private String shortFingerprint(String secret) throws IOException {
        String fingerprint = JiaguServerClient.sha256(secret.getBytes(StandardCharsets.UTF_8));
        return fingerprint.substring(0, Math.min(12, fingerprint.length()));
    }

    private byte[] readStream(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    private byte[] intToBytes(int value) {
        return new byte[]{
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }
}
