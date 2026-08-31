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
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

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
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.R8;
import com.android.tools.r8.R8Command;
import com.android.tools.r8.origin.Origin;

import org.gradle.api.tasks.Internal;
import java.nio.charset.StandardCharsets;

/**
 * 核心加固打包任务：
 * 负责遍历所有的 Class 文件，将壳代码放入输出 Jar，将业务代码加密。
 */
@DisableCachingByDefault(because = "Creates and updates a server-side release and embeds its identity")
public abstract class JiaguTask extends DefaultTask {

    @Internal
    public abstract Property<String> getPackageName();

    @Internal
    public abstract Property<String> getVersionName();

    @Internal
    public abstract Property<Integer> getVersionCode();

    @Internal
    public abstract Property<String> getServerUrl();

    @Internal
    public abstract Property<String> getCompanyId();

    @Internal
    public abstract Property<String> getCompanyApiKey();

    @Internal
    public abstract Property<String> getCertificateSha256();

    @Internal
    public abstract ListProperty<String> getCertificateSha256Digests();

    @Internal
    public abstract RegularFileProperty getResourcePackage();

    @Internal
    public abstract DirectoryProperty getMergedAssets();

    @Internal
    public abstract org.gradle.api.file.ConfigurableFileCollection getNativeInputs();

    @Internal
    public abstract Property<Boolean> getPublish();

    @Input
    public abstract Property<Boolean> getAntiDebugEnabled();

    @Internal
    public abstract Property<String> getBuildInvocationId();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ListProperty<RegularFile> getAllJars();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ListProperty<Directory> getAllDirectories();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract org.gradle.api.file.ConfigurableFileCollection getBootClasspath();

    @Input
    public abstract Property<Boolean> getMinifyEnabled();

    @Input
    public abstract Property<Boolean> getDebuggable();

    @Input
    public abstract Property<Integer> getMinApiLevel();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract org.gradle.api.file.ConfigurableFileCollection getProguardFiles();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract org.gradle.api.file.ConfigurableFileCollection getConsumerProguardFiles();

    @Internal
    public abstract DirectoryProperty getNdkDirectory();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @Internal
    public abstract DirectoryProperty getOutJniLibsDir();

    @Internal
    public abstract RegularFileProperty getReleaseMetadataFile();

    @OutputFile
    public abstract RegularFileProperty getPayloadFile();

    @OutputFile
    public abstract RegularFileProperty getBusinessDexSha256File();

    @Optional
    @OutputFile
    public abstract RegularFileProperty getBusinessMappingFile();

    @TaskAction
    public void execute() throws IOException {
        long taskStartedAt = System.nanoTime();
        Map<String, Long> stageTimes = new LinkedHashMap<>();
        getLogger().lifecycle("[Jiagu][计时] 加固任务开始: {}", getPath());

        long stageStartedAt = System.nanoTime();
        File outputJarFile = getOutputJar().get().getAsFile();
        File payloadFile = getPayloadFile().get().getAsFile();
        File businessDexSha256File = getBusinessDexSha256File().get().getAsFile();
        Files.createDirectories(outputJarFile.toPath().getParent());
        Files.createDirectories(payloadFile.toPath().getParent());
        Files.createDirectories(businessDexSha256File.toPath().getParent());

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
            if (getMinifyEnabled().get()) {
                runR8(tempBusinessJar, outputJarFile, tempDexDir);
                finishStage("R8 业务代码裁剪", stageStartedAt, stageTimes);
            } else {
                D8Command command = D8Command.builder()
                        .addProgramFiles(tempBusinessJar.toPath())
                        .setOutput(tempDexDir, OutputMode.DexIndexed)
                        .setMinApiLevel(getMinApiLevel().get())
                        .build();
                D8.run(command);
                finishStage("D8 转换（App 未启用 minify）", stageStartedAt, stageTimes);
            }

            File[] dexFiles = tempDexDir.toFile().listFiles((dir, name) -> name.endsWith(".dex"));
            if (dexFiles != null && dexFiles.length > 0) {
                Arrays.sort(dexFiles, Comparator.comparing(File::getName));
                String businessDexSha256 = hashFiles("JIAGU-BUSINESS-DEX-V1", Arrays.asList(dexFiles));
                Files.write(businessDexSha256File.toPath(), businessDexSha256.getBytes(StandardCharsets.UTF_8));
                // 按照文件名排序，确保 classes.dex, classes2.dex 等顺序一致
                Arrays.sort(dexFiles, Comparator.comparing(File::getName));
                
                // 生成构建期 JG3 容器。后续 Release 任务在本地使用 Release Key 加密为 JGLP，
                // 并把密文内置到 APK；服务端只保存摘要和受保护的 Key。
                long rawDexBytes = 0;
                long compressedDexBytes = 0;
                long uncompressedPayloadBytes = 0;
                stageStartedAt = System.nanoTime();
                try (FileOutputStream fos = new FileOutputStream(payloadFile)) {
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
                logCompression("JG3 Payload", uncompressedPayloadBytes, payloadFile.length());
                getLogger().lifecycle("[Jiagu] 业务 DEX Payload 已准备: {}", payloadFile);
            } else {
                throw new IOException("D8 failed to produce any DEX files");
            }
        } catch (Exception e) {
            String message = "[Jiagu] 业务 DEX 转换与 Payload 生成失败";
            getLogger().error(message, e);
            throw new IOException(message, e);
        } finally {
            // 清理临时文件
            deleteDirectory(tempDexDir.toFile());
            tempBusinessJar.delete();
        }
        
        getLogger().lifecycle("[Jiagu] 业务代码加固阶段完成。输出: {}", outputJarFile.getName());
        long totalMs = elapsedMillis(taskStartedAt);
        getLogger().lifecycle("[Jiagu][计时] ===== 加固阶段耗时汇总 =====");
        for (Map.Entry<String, Long> entry : stageTimes.entrySet()) {
            getLogger().lifecycle("[Jiagu][计时] {}: {}", entry.getKey(), formatDuration(entry.getValue()));
        }
        getLogger().lifecycle("[Jiagu][计时] 总耗时: {}", formatDuration(totalMs));
    }

    private void runR8(File businessJar, File shellJar, Path output) throws Exception {
        R8Command.Builder commandBuilder = R8Command.builder()
                .addProgramFiles(businessJar.toPath())
                .addLibraryFiles(getBootClasspath().getFiles().stream()
                        .map(File::toPath).collect(java.util.stream.Collectors.toList()))
                .addLibraryFiles(shellJar.toPath())
                .setOutput(output, OutputMode.DexIndexed)
                .setMinApiLevel(getMinApiLevel().get())
                .setMode(getDebuggable().get() ? CompilationMode.DEBUG : CompilationMode.RELEASE)
                .setDisableTreeShaking(false)
                .setDisableMinification(false)
                .setProguardMapOutputPath(getBusinessMappingFile().get().getAsFile().toPath());
        List<Path> configuredRules = getProguardFiles().getFiles().stream()
                .filter(File::isFile).map(File::toPath)
                .collect(java.util.stream.Collectors.toList());
        if (!configuredRules.isEmpty()) {
            commandBuilder.addProguardConfigurationFiles(configuredRules);
        }
        List<String> consumerRules = readConsumerProguardRules();
        if (!consumerRules.isEmpty()) {
            commandBuilder.addProguardConfiguration(consumerRules, Origin.unknown());
        }
        commandBuilder.addProguardConfiguration(pluginSafetyRules(), Origin.unknown());
        R8.run(commandBuilder.build());
    }

    private List<String> readConsumerProguardRules() throws IOException {
        List<File> artifacts = new ArrayList<>(getConsumerProguardFiles().getFiles());
        artifacts.sort(Comparator.comparing(File::getAbsolutePath));
        List<String> rules = new ArrayList<>();
        for (File artifact : artifacts) {
            if (artifact.isDirectory()) {
                try (java.util.stream.Stream<Path> paths = Files.walk(artifact.toPath())) {
                    for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)
                            .sorted()::iterator) {
                        rules.add("# " + artifact.toPath().relativize(path).toString().replace('\\', '/'));
                        rules.addAll(Files.readAllLines(path, StandardCharsets.UTF_8));
                    }
                }
            } else if (artifact.isFile()) {
                rules.add("# " + artifact.getName());
                rules.addAll(Files.readAllLines(artifact.toPath(), StandardCharsets.UTF_8));
            }
        }
        return rules;
    }

    private List<String> pluginSafetyRules() {
        return Arrays.asList(
                "-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable",
                "-repackageclasses 'io.github.xjc.jiagu.payload.r8'",
                "-keep class * extends android.app.Application { *; }",
                "-keep class * extends android.app.Activity { *; }",
                "-keep class * extends android.app.Service { *; }",
                "-keep class * extends android.content.BroadcastReceiver { *; }",
                "-keep class * extends android.content.ContentProvider { *; }",
                "-keep class * implements androidx.startup.Initializer { *; }",
                "-keep class * extends android.view.View { public <init>(...); }",
                "-keepclassmembers class * { native <methods>; }"
        );
    }

    private void processEntry(JarOutputStream shellJos, JarOutputStream businessJos, String name, byte[] data, Set<String> processedNames) throws IOException {
        if (processedNames.contains(name)) {
            return;
        }

        // 适度回调：保留 R 类在壳中。
        // 完全移除 R 类可能导致某些系统资源（如图标、主题）在壳 Application 阶段解析失败。
        boolean shouldKeepInShell = shouldKeepInShell(name);

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

    static boolean shouldKeepInShell(String name) {
        return name.startsWith("io/github/xjc/jiagu/") ||
                name.startsWith("com/google/crypto/tink/") ||
                name.startsWith("com/google/android/play/") ||
                name.startsWith("com/google/android/gms/") ||
                // Device authorization runs before the encrypted business DEX is loaded.
                // Keep its complete HTTP stack in the shell so NetworkHelper can initialize.
                name.startsWith("okhttp3/") ||
                name.startsWith("okio/") ||
                name.startsWith("org/conscrypt/") ||
                name.startsWith("kotlin/") ||
                name.startsWith("androidx/startup/") ||
                name.startsWith("org/jetbrains/annotations/") ||
                name.startsWith("org/jspecify/annotations/") ||
                name.contains("/R$") || name.endsWith("/R.class");
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
                "\"serverKeyId\":" + JiaguServerClient.json(publicConfig.serverKeyId) + "," +
                "\"serverPublicKey\":" + JiaguServerClient.json(publicConfig.serverPublicKey) + "," +
                "\"wrapAlgorithm\":\"RSA-OAEP-SHA1\"," +
                "\"integrityMode\":" + JiaguServerClient.json(publicConfig.integrityMode) + "," +
                "\"integrityCloudProjectNumber\":" + publicConfig.integrityCloudProjectNumber +
                "}";
    }

    private void writeReleaseMetadata(JiaguServerClient.Release release) throws IOException {
        File target = getReleaseMetadataFile().get().getAsFile();
        Files.createDirectories(target.toPath().getParent());
        Files.write(target.toPath(), ("{\"releaseId\":" + JiaguServerClient.json(release.releaseId) +
                ",\"status\":" + JiaguServerClient.json(release.status) +
                ",\"buildInvocationId\":" + JiaguServerClient.json(getBuildInvocationId().get()) + "}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private String hashResourcePackage() throws IOException {
        if (!getResourcePackage().isPresent() || !getResourcePackage().get().getAsFile().isFile()) {
            return hashEntryValues("JIAGU-RESOURCES-V1", new ArrayList<>());
        }
        List<EntryValue> entries = new ArrayList<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(getResourcePackage().get().getAsFile())) {
            Enumeration<? extends java.util.zip.ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                java.util.zip.ZipEntry entry = all.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !(name.equals("AndroidManifest.xml") || name.equals("resources.arsc") ||
                        name.startsWith("res/") || name.startsWith("assets/"))) continue;
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
                            File library = path.toFile();
                            entries.add(new EntryValue(nativePath(library), strippedNativeBytes(library)));
                        }
                    }
                }
                continue;
            }
            if (!file.isFile()) continue;
            if (file.getName().endsWith(".so") && !file.getName().equals("liblog_ext.so")) {
                entries.add(new EntryValue(nativePath(file), strippedNativeBytes(file)));
            } else if (file.getName().endsWith(".aar") || file.getName().endsWith(".zip")) {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
                    Enumeration<? extends java.util.zip.ZipEntry> all = zip.entries();
                    while (all.hasMoreElements()) {
                        java.util.zip.ZipEntry entry = all.nextElement();
                        String name = entry.getName().replace('\\', '/');
                        if (entry.isDirectory() || !name.startsWith("jni/") || !name.endsWith(".so") ||
                                name.endsWith("/liblog_ext.so")) continue;
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
            Files.copy(library.toPath(), temporary.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            File prebuilt = new File(getNdkDirectory().get().getAsFile(), "toolchains/llvm/prebuilt");
            File[] hosts = prebuilt.listFiles(File::isDirectory);
            if (hosts == null || hosts.length == 0) throw new IOException("NDK llvm prebuilt directory is unavailable");
            Arrays.sort(hosts, Comparator.comparing(File::getName));
            File strip = new File(hosts[0], "bin/llvm-strip" + (isWindows() ? ".exe" : ""));
            if (!strip.isFile()) throw new IOException("NDK llvm-strip is unavailable: " + strip);
            runCommand(Arrays.asList(strip.getAbsolutePath(), "--strip-unneeded", temporary.getAbsolutePath()),
                    temporary.getParentFile(), "normalize native library " + library.getName());
            return Files.readAllBytes(temporary.toPath());
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private String nativePath(File file) {
        String value = file.getPath().replace('\\', '/');
        for (String abi : Arrays.asList("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
            if (value.contains("/" + abi + "/")) return abi + "/" + file.getName();
        }
        return "unknown/" + file.getName();
    }

    private String hashFiles(String domain, List<File> files) throws IOException {
        List<EntryValue> entries = new ArrayList<>();
        for (File file : files) entries.add(new EntryValue(file.getName(), Files.readAllBytes(file.toPath())));
        return hashEntryValues(domain, entries);
    }

    private String hashEntryValues(String domain, List<EntryValue> entries) throws IOException {
        java.util.TreeMap<String, byte[]> unique = new java.util.TreeMap<>();
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

    private static String canonical(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) result.append(value.getBytes(StandardCharsets.UTF_8).length)
                .append(':').append(value).append('\n');
        return result.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static List<String> sortedUnique(List<String> values) {
        java.util.TreeSet<String> set = new java.util.TreeSet<>();
        for (String value : values) if (value != null && !value.trim().isEmpty()) set.add(value.trim());
        return new ArrayList<>(set);
    }

    private static String jsonArray(List<String> values) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) result.append(',');
            result.append(JiaguServerClient.json(values.get(i)));
        }
        return result.append(']').toString();
    }

    private static final class EntryValue {
        final String path;
        final byte[] data;
        EntryValue(String path, byte[] data) { this.path = path; this.data = data; }
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
