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
import java.security.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getPublicKeyPath();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getPublicKeyJsonKey();

    @Input
    public abstract Property<Boolean> getEnableMultiVersion();

    @Input
    public abstract Property<Integer> getKeyExpiryDays();

    @InputFiles
    public abstract ListProperty<RegularFile> getAllJars();

    @InputFiles
    public abstract ListProperty<Directory> getAllDirectories();

    @Internal
    public abstract RegularFileProperty getKeysFile();

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
        // 生成或复用 Session Key，仅用于构建期加密，不写入 Manifest。
        byte[] sessionKey = handleKeyManagement(versionCode);
        finishStage("密钥准备", stageStartedAt, stageTimes);

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
                
                // 先生成与 ABI 无关的加密载荷，再为每个 ABI 链接成真实 ELF。
                File tempPayload = File.createTempFile("jiagu_payload", ".bin");
                long rawDexBytes = 0;
                long compressedDexBytes = 0;
                long uncompressedPayloadBytes;
                stageStartedAt = System.nanoTime();
                try (FileOutputStream fos = new FileOutputStream(tempPayload)) {
                    // JG2 表示 DEX 在加密前经过 zlib 压缩；运行时据此选择解压路径。
                    fos.write("JG2\0".getBytes(StandardCharsets.UTF_8));
                    
                    // 2. 写入加密元数据与压缩主体。压缩必须发生在加密前，否则密文几乎不可压缩。
                    java.util.List<byte[]> metaEntries = new java.util.ArrayList<>();
                    long currentOffset = 0;
                    java.io.ByteArrayOutputStream bodyStream = new java.io.ByteArrayOutputStream();

                    for (File dexFile : dexFiles) {
                        byte[] dexData = Files.readAllBytes(dexFile.toPath());
                        byte[] compressedDex = compress(dexData);
                        byte[] encryptedDex = encrypt(compressedDex, sessionKey);
                        rawDexBytes += dexData.length;
                        compressedDexBytes += compressedDex.length;
                        
                        java.nio.ByteBuffer entry = java.nio.ByteBuffer.allocate(12);
                        entry.putInt((int)currentOffset);
                        entry.putInt(encryptedDex.length);
                        entry.putInt(dexData.length);
                        metaEntries.add(entry.array());

                        bodyStream.write(encryptedDex);
                        currentOffset += encryptedDex.length;
                    }

                    java.nio.ByteBuffer metaBlock = java.nio.ByteBuffer.allocate(4 + metaEntries.size() * 12);
                    metaBlock.putInt(dexFiles.length);
                    for (byte[] e : metaEntries) metaBlock.put(e);

                    byte[] encryptedMeta = encrypt(metaBlock.array(), sessionKey);
                    fos.write(intToBytes(encryptedMeta.length));
                    fos.write(encryptedMeta);
                    fos.write(bodyStream.toByteArray());

                    // 加密只增加固定的 IV + Tag；用于展示采用压缩前格式时的载荷大小。
                    uncompressedPayloadBytes = 4L + 4L + encryptedMeta.length
                            + rawDexBytes + (long) dexFiles.length * 28L;
                }
                finishStage("DEX 压缩与加密", stageStartedAt, stageTimes);
                logCompression("DEX 数据", rawDexBytes, compressedDexBytes);
                logCompression("加密载荷", uncompressedPayloadBytes, tempPayload.length());

                try {
                    stageStartedAt = System.nanoTime();
                    buildPayloadLibraries(tempPayload, jniLibsDir, uncompressedPayloadBytes);
                    finishStage("四 ABI ELF 并行链接", stageStartedAt, stageTimes);
                } finally {
                    Files.deleteIfExists(tempPayload.toPath());
                }

                getLogger().lifecycle("[Jiagu] 加密载荷 ELF 构建成功: liblog_ext.so 已生成至四个 ABI 目录");
            } else {
                throw new IOException("D8 failed to produce any DEX files");
            }
        } catch (NdkConfigurationException e) {
            getLogger().error(e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            String message = "[Jiagu] DEX 转换或加密载荷 ELF 构建失败";
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

    private byte[] handleKeyManagement(int versionCode) {
        File keyFile = getKeysFile().get().getAsFile();
        String jsonKey = getPublicKeyJsonKey().getOrElse("akmKeys");
        String pkgName = getPackageName().get();
        String versionName = getVersionName().get();

        // 动态派生 Master Key: SHA-256(pkg:version:salt)
        byte[] masterKey;
        try {
            String input = pkgName + ":" + versionName + ":JIAGU_SALT_2026";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            masterKey = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }

        try {
            // 1. 本地密钥存储读取
            String content = "{}";
            if (keyFile.exists()) {
                content = new String(Files.readAllBytes(keyFile.toPath()), StandardCharsets.UTF_8);
            }

            // 2. 检查当前版本是否已存在合法的 Key
            String versionPattern = "\"" + versionCode + "\":\\s*\\{([^}]+)\\}";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(versionPattern, java.util.regex.Pattern.DOTALL).matcher(content);
            if (m.find()) {
                String body = m.group(1);
                String nonceHex = extractJsonField(body, "nonce");
                String bksBlobHex = extractJsonField(body, "bksBlob");

                if (nonceHex != null && bksBlobHex != null) {
                    getLogger().lifecycle("[Jiagu] 检测到版本 {} 已存在密钥，正在尝试复用...", versionCode);
                    try {
                        byte[] nonce = hexToBytes(nonceHex);
                        byte[] bksBlob = hexToBytes(bksBlobHex);

                        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(128, nonce));
                        byte[] sessionKey = cipher.doFinal(bksBlob);

                        getLogger().lifecycle("[Jiagu] 成功复用版本 {} 的现有密钥。", versionCode);
                        
                        getLogger().lifecycle("**************************************************");
                        getLogger().lifecycle("[Jiagu] 现有密钥块内容 (请确保已部署至服务器 {} 节点):", jsonKey);
                        getLogger().lifecycle(m.group(0));
                        getLogger().lifecycle("**************************************************");
                        
                        return sessionKey;
                    } catch (Exception e) {
                        getLogger().warn("[Jiagu] 复用密钥失败（可能 Master Key 已更改），将重新生成: {}", e.getMessage());
                    }
                }
            }

            // 3. 生成工业级 KMS 结构数据 (重新生成或新生成)
            getLogger().lifecycle("[Jiagu] 正在为版本 {} 生成工业级加固密钥块...", versionCode);
            
            // 生成 Session Key (真正加密 DEX 的钥匙)
            byte[] sessionKey = new byte[32];
            SecureRandom.getInstanceStrong().nextBytes(sessionKey);

            // 生成 Salt 和 Nonce
            byte[] salt = new byte[16];
            byte[] nonce = new byte[12];
            SecureRandom.getInstanceStrong().nextBytes(salt);
            SecureRandom.getInstanceStrong().nextBytes(nonce);
            
            // 生成 bksBlob (使用 Master Key 加密 Session Key)
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(128, nonce));
            byte[] bksBlob = cipher.doFinal(sessionKey);

            String saltHex = bytesToHex(salt);
            String nonceHex = bytesToHex(nonce);
            String bksBlobHex = bytesToHex(bksBlob);

            // 4. 更新本地 JSON 文件 (KMS 结构)
            String newEntry = String.format("  \"%d\": {\n    \"keyVersion\": 1,\n    \"enabled\": true,\n    \"salt\": \"%s\",\n    \"nonce\": \"%s\",\n    \"bksBlob\": \"%s\"\n  }", 
                                            versionCode, saltHex, nonceHex, bksBlobHex);
            
            if (content.trim().equals("{}") || content.trim().isEmpty()) {
                content = "{\n" + newEntry + "\n}";
            } else {
                int lastBrace = content.lastIndexOf("}");
                if (lastBrace != -1) {
                    content = content.substring(0, lastBrace).trim();
                    if (content.endsWith("{")) {
                        content += "\n" + newEntry + "\n}";
                    } else {
                        content += ",\n" + newEntry + "\n}";
                    }
                }
            }
            Files.write(keyFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

            getLogger().lifecycle("**************************************************");
            getLogger().lifecycle("[Jiagu] 工业级加固密钥包已保存至: {}", keyFile.getAbsolutePath());
            getLogger().lifecycle("[Jiagu] 请将以下 JSON 结构部署到您的密钥分发服务器 ({} 节点):", jsonKey);
            getLogger().lifecycle(newEntry);
            getLogger().lifecycle("**************************************************");
            
            return sessionKey;
        } catch (Exception e) {
            getLogger().error("[Jiagu] 密钥管理失败: {}", e.getMessage());
            throw new RuntimeException("Jiagu Failure", e);
        }
    }

    private static String extractJsonField(String json, String fieldName) {
        String pattern = "\"" + fieldName + "\":\\s*\"([^\"]+)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String fetchRemotePublicKey(String urlString) {
        try {
            java.net.URL url = new java.net.URL(urlString);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() != 200) {
                getLogger().warn("[Jiagu] 网络请求失败，状态码: {}", conn.getResponseCode());
                return null;
            }
            try (InputStream is = conn.getInputStream()) {
                return new String(readStream(is), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            getLogger().warn("[Jiagu] 无法访问网络公钥路径: {}", e.getMessage());
            return null;
        }
    }

    private byte[] encrypt(byte[] data, byte[] sessionKey) {
        try {
            // 1. 使用传入的 Session Key
            byte[] aesKey = new byte[32]; // 确保 256 位
            System.arraycopy(sessionKey, 0, aesKey, 0, Math.min(sessionKey.length, 32));

            // 2. 使用 AES-GCM 加密 DEX 数据
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[12];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] ciphertextWithTag = cipher.doFinal(data);

            // 3. 构造加密包: IV(12) + Ciphertext + Tag
            byte[] result = new byte[iv.length + ciphertextWithTag.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertextWithTag, 0, result, iv.length, ciphertextWithTag.length);
            
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
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
