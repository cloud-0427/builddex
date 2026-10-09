package io.github.xjc.dexreport;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.OutputFile;
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
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;

import java.nio.charset.StandardCharsets;

/**
 * 核心加固打包任务：
 * 负责遍历所有的 Class 文件，将壳代码放入输出 Jar，将业务代码加密。
 */
@DisableCachingByDefault(because = "Produces locally protected class lanes and audit artifacts")
public abstract class JiaguTask extends DefaultTask {
    private boolean androidXFactory;

    @Input
    public abstract Property<Boolean> getAntiDebugEnabled();

    @Input
    public abstract Property<Boolean> getPayloadCompressionEnabled();

    @Input
    @Optional
    public abstract Property<String> getStartupLogUploaderClass();

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
    public abstract Property<Boolean> getRuntimeR8Enabled();

    @Input
    public abstract Property<String> getRuntimeR8Rules();



    @Input
    public abstract Property<String> getPayloadSelectionMode();

    @Input
    public abstract org.gradle.api.provider.SetProperty<String> getPayloadIncludePackages();

    @Input
    public abstract org.gradle.api.provider.SetProperty<String> getShellKeepPackages();

    @Input
    public abstract org.gradle.api.provider.SetProperty<String> getShellKeepClasses();

    @Input
    public abstract Property<String> getStartupComponentPolicy();

    @Input
    public abstract Property<String> getPayloadR8Policy();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMergedManifest();

    @Input
    public abstract Property<Boolean> getDebuggable();

    @Input
    public abstract Property<Integer> getMinApiLevel();

@OutputFile
    public abstract RegularFileProperty getOutputJar();

    @OutputFile
    public abstract RegularFileProperty getPayloadFile();

    @OutputFile
    public abstract RegularFileProperty getBusinessDexSha256File();

    @OutputFile
    public abstract RegularFileProperty getShellKeepRulesFile();

    @OutputFile
    @Optional
    public abstract RegularFileProperty getRuntimeMappingFile();

    @OutputFile
    public abstract RegularFileProperty getServiceDescriptorsFile();

    @OutputFile
    public abstract RegularFileProperty getInputIndexFile();

    @TaskAction
    public void execute() throws IOException {
        androidXFactory = Files.readString(getMergedManifest().get().getAsFile().toPath()).contains("LocalAndroidXComponentFactory");
        long taskStartedAt = System.nanoTime();
        Map<String, Long> stageTimes = new LinkedHashMap<>();
        getLogger().lifecycle("[Jiagu][计时] 加固任务开始: {}", getPath());

        String payloadMode = getPayloadSelectionMode().get();
        Set<String> payloadPackages = getPayloadIncludePackages().get();
        Set<String> shellPackages = getShellKeepPackages().get();
        Set<String> shellClasses = getShellKeepClasses().get();
        String startupPolicy = getStartupComponentPolicy().get();
        try {
            PayloadRouting.validate(payloadMode, payloadPackages, startupPolicy, getPayloadR8Policy().get());
            PayloadRouting.validatePackages(shellPackages, "shellKeepPackages");
            PayloadRouting.validateClasses(shellClasses, "shellKeepClasses");
        } catch (IllegalArgumentException error) {
            throw new IOException("[Jiagu] Invalid Payload routing configuration: " + error.getMessage(), error);
        }
        Set<String> startupClasses;
        try {
            startupClasses = java.util.Collections.emptySet();
        } catch (Exception error) {
            throw new IOException("[Jiagu] Failed to inspect merged manifest startup components", error);
        }
        getLogger().lifecycle("[Jiagu][Routing] mode={}, payloadPackages={}, startupClasses={}",
                payloadMode, payloadPackages.size(), startupClasses.size());

        long stageStartedAt = System.nanoTime();
        File outputJarFile = getOutputJar().get().getAsFile();
        File payloadFile = getPayloadFile().get().getAsFile();
        File businessDexSha256File = getBusinessDexSha256File().get().getAsFile();
        Files.createDirectories(outputJarFile.toPath().getParent());
        Files.createDirectories(payloadFile.toPath().getParent());
        Files.createDirectories(businessDexSha256File.toPath().getParent());

        // ... 省略部分中间 JAR 处理逻辑 (与之前相同) ...

        Map<String, SeenEntry> processedNames = new LinkedHashMap<>();
        ServiceDescriptors services = new ServiceDescriptors();
        File tempBusinessJar = File.createTempFile("business-d8", ".jar");
        File tempPassThroughJar = File.createTempFile("shell-pass-through", ".jar");
        File tempRuntimeJar = File.createTempFile("shell-runtime", ".jar");
        File tempCombinedShellJar = File.createTempFile("shell-combined", ".jar");
        tempBusinessJar.deleteOnExit();
        tempPassThroughJar.deleteOnExit();
        tempRuntimeJar.deleteOnExit();
        tempCombinedShellJar.deleteOnExit();

        List<InputArtifact> inputArtifacts = inspectInputArtifacts();
        writeInputIndex(inputArtifacts);
        int detectedR8Artifacts = 0;
        for (InputArtifact artifact : inputArtifacts) {
            if (artifact.result.classification == R8ArtifactDetector.Classification.CONFLICTING_EVIDENCE) {
                throw new IOException("[Jiagu] Conflicting R8 evidence in input " + artifact.path
                        + ": " + artifact.result.evidence);
            }
            if (artifact.result.classification == R8ArtifactDetector.Classification.R8_PROCESSED) {
                detectedR8Artifacts++;
                getLogger().lifecycle("[Jiagu][Input] detected upstream R8 artifact: {} -> {} ({})",
                        artifact.path, artifact.action(getRuntimeR8Enabled().get()), artifact.result.evidence);
            }
        }
        getLogger().lifecycle("[Jiagu][Input] {} artifacts inspected; {} already processed by R8; audit: {}",
                inputArtifacts.size(), detectedR8Artifacts, getInputIndexFile().get().getAsFile());
        int[] businessClasses = {0};

        getLogger().lifecycle("[Jiagu] 正在执行全量代码扫描与分离...");
        stageStartedAt = System.nanoTime();

        try (JarOutputStream shellJos = new JarOutputStream(new FileOutputStream(tempPassThroughJar));
             JarOutputStream runtimeJos = new JarOutputStream(new FileOutputStream(tempRuntimeJar));
             JarOutputStream businessJos = new JarOutputStream(new FileOutputStream(tempBusinessJar))) {
            // 中间业务 JAR 只供紧随其后的 D8 使用，无需耗时做 ZIP 压缩。
            // 壳 JAR 使用快速压缩，在不明显增大最终产物的前提下降低扫描阶段 CPU 开销。
            shellJos.setLevel(Deflater.BEST_SPEED);
            businessJos.setLevel(Deflater.NO_COMPRESSION);

            // 1. 处理所有输入的 JAR 文件（包括依赖库）
            long inputStartedAt = System.nanoTime();
            List<RegularFile> jars = getAllJars().get();
            for (int artifactIndex = 0; artifactIndex < jars.size(); artifactIndex++) {
                RegularFile jarFile = jars.get(artifactIndex);
                InputArtifact artifact = inputArtifacts.get(artifactIndex);
                try (JarFile inputJar = new JarFile(jarFile.getAsFile())) {
                    Enumeration<JarEntry> entries = inputJar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;

                        try (InputStream is = inputJar.getInputStream(entry)) {
                            byte[] data = readStream(is);
                            processEntry(shellJos,
                                    businessJos,
                                    entry.getName(), data, processedNames, services,
                                    jarFile.getAsFile().getAbsolutePath() + "!/" + entry.getName(),
                                    businessClasses, payloadMode, payloadPackages, shellPackages,
                                    shellClasses, startupClasses, startupPolicy, runtimeJos);
                        }
                    }
                }
            }
            finishStage("依赖 JAR 扫描与分离", inputStartedAt, stageTimes);

            // 2. 处理所有目录（当前项目的编译产物）
            inputStartedAt = System.nanoTime();
            List<Directory> directories = getAllDirectories().get();
            int directoryOffset = jars.size();
            for (int directoryIndex = 0; directoryIndex < directories.size(); directoryIndex++) {
                File dirFile = directories.get(directoryIndex).getAsFile();
                InputArtifact artifact = inputArtifacts.get(directoryOffset + directoryIndex);
                try (java.util.stream.Stream<Path> paths = Files.walk(dirFile.toPath())) {
                    for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile).sorted()::iterator) {
                        String relativePath = dirFile.toPath().relativize(path).toString().replace('\\', '/');
                        processEntry(shellJos,
                                businessJos,
                                relativePath, Files.readAllBytes(path), processedNames, services,
                                path.toAbsolutePath().toString(),
                                businessClasses, payloadMode, payloadPackages, shellPackages,
                                shellClasses, startupClasses, startupPolicy, runtimeJos);
                    }
                }
            }
            finishStage("目录扫描与分离", inputStartedAt, stageTimes);
            services.write(shellJos);
            services.write(businessJos);
            try (JarOutputStream catalog = new JarOutputStream(Files.newOutputStream(
                    getServiceDescriptorsFile().get().getAsFile().toPath()))) {
                services.write(catalog);
            }
        }
        getLogger().lifecycle("[Jiagu][计时] 代码扫描与分离总耗时 {}",
                formatDuration(elapsedMillis(stageStartedAt)));

        // 3. 将业务代码 JAR 转换为 DEX
        getLogger().lifecycle("[Jiagu] 正在将业务代码转换为 DEX...");
        Path tempDexDir = Files.createTempDirectory("jiagu_dex");
        try {
            stageStartedAt = System.nanoTime();
            LocalClassBoundary.verify(tempPassThroughJar, tempRuntimeJar, tempBusinessJar, configuredUploaderClass());
            runD8(tempBusinessJar, tempPassThroughJar, tempRuntimeJar, tempDexDir);
            finishStage("D8 转换（业务字节码保持原状）", stageStartedAt, stageTimes);

            File[] dexFiles = tempDexDir.toFile().listFiles((dir, name) -> name.endsWith(".dex"));
            if (dexFiles != null && dexFiles.length > 0) {
                Arrays.sort(dexFiles, Comparator.comparingInt(file -> file.getName().equals("classes.dex") ? 1 : Integer.parseInt(file.getName().substring(7, file.getName().length() - 4))));
                verifyNoDuplicateDexClasses(dexFiles);
                Path shellAbiTarget = tempRuntimeJar.toPath();
                if (!getRuntimeR8Enabled().get()) {
                    mergeShellJars(tempPassThroughJar, tempRuntimeJar, tempCombinedShellJar);
                    shellAbiTarget = tempCombinedShellJar.toPath();
                }
                ShellKeepRules.generate(
                        Arrays.stream(dexFiles).map(File::toPath)
                                .collect(java.util.stream.Collectors.toList()),
                        shellAbiTarget,
                        getBootClasspath().getFiles().stream().map(File::toPath)
                                .collect(java.util.stream.Collectors.toList()),
                        getShellKeepRulesFile().get().getAsFile().toPath());
                List<String> generatedShellRules = new ArrayList<>(services.keepRules());
                // Payload 与壳由不同 ClassLoader 加载。把可改名的壳类（包括 R8 新建的
                // external synthetic）限制在壳专属命名域；TraceReferences 生成的 ABI
                // keep 规则仍会让 Payload 实际引用的壳类保持原名。
                generatedShellRules.add("-repackageclasses 'io.github.xjc.jiagu.shell.r8'");
                String uploaderClass = configuredUploaderClass();
                if (uploaderClass != null) {
                    generatedShellRules.add("-keep class " + uploaderClass + " { *; }");
                    generatedShellRules.add("-keep class " + uploaderClass + "$* { *; }");
                }
                Files.write(getShellKeepRulesFile().get().getAsFile().toPath(), generatedShellRules,
                        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
                getLogger().lifecycle("[Jiagu] 已生成业务 DEX 引用的壳类/成员白名单: {}",
                        getShellKeepRulesFile().get().getAsFile());
                String businessDexSha256 = hashFiles("JIAGU-BUSINESS-DEX-V1", Arrays.asList(dexFiles));
                Files.write(businessDexSha256File.toPath(), businessDexSha256.getBytes(StandardCharsets.UTF_8));
                // 按照文件名排序，确保 classes.dex, classes2.dex 等顺序一致
                Arrays.sort(dexFiles, Comparator.comparingInt(file -> file.getName().equals("classes.dex") ? 1 : Integer.parseInt(file.getName().substring(7, file.getName().length() - 4))));
                
                // 生成构建期 JG3 容器。后续 Release 任务在本地使用 Release Key 加密为 JGLP，
                // 并把密文内置到 APK；服务端只保存摘要和受保护的 Key。
                long rawDexBytes = 0;
                long compressedDexBytes = 0;
                long uncompressedPayloadBytes = 0;
                stageStartedAt = System.nanoTime();
                try (FileOutputStream fos = new FileOutputStream(payloadFile)) {
                    boolean compressPayload = getPayloadCompressionEnabled().get();
                    fos.write((compressPayload ? "JG3\0" : "JG4\0").getBytes(StandardCharsets.UTF_8));
                    fos.write(intToBytes(dexFiles.length));

                    java.util.List<byte[]> compressedEntries = new java.util.ArrayList<>();
                    long currentOffset = 0;
                    for (File dexFile : dexFiles) {
                        byte[] dexData = Files.readAllBytes(dexFile.toPath());
                        byte[] compressedDex = compressPayload ? compress(dexData) : dexData;
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
                finishStage(getPayloadCompressionEnabled().get() ? "DEX 压缩与 JG3 封装" : "DEX 未压缩 JG4 封装", stageStartedAt, stageTimes);
                logCompression("DEX 数据", rawDexBytes, compressedDexBytes);
                logCompression("JG3 Payload", uncompressedPayloadBytes, payloadFile.length());
                getLogger().lifecycle("[Jiagu] 业务 DEX Payload 已准备: {}", payloadFile);
            } else {
                throw new IOException("D8 failed to produce any DEX files");
            }

            stageStartedAt = System.nanoTime();
            if (getRuntimeR8Enabled().get()) {
                Path runtimeRules = Files.createTempFile("jiagu-runtime-r8", ".pro");
                Path r8Output = Files.createTempFile("jiagu-runtime-r8", ".jar");
                try {
                    List<String> rules = new ArrayList<>();
                    String bundledRules = getRuntimeR8Rules().get();
                    if (!bundledRules.trim().isEmpty()) rules.addAll(Arrays.asList(bundledRules.split("\\R")));
                    if (Files.isRegularFile(getShellKeepRulesFile().get().getAsFile().toPath())) {
                        rules.addAll(Files.readAllLines(getShellKeepRulesFile().get().getAsFile().toPath(),
                                StandardCharsets.UTF_8));
                    }
                    Files.write(runtimeRules, rules, StandardCharsets.UTF_8);
                    List<Path> libraries = new ArrayList<>();
                    libraries.add(tempPassThroughJar.toPath());
                    for (File boot : getBootClasspath().getFiles()) libraries.add(boot.toPath());
                    RuntimeR8Processor.run(tempRuntimeJar.toPath(), libraries,
                            java.util.Collections.singletonList(runtimeRules), r8Output,
                            getRuntimeMappingFile().get().getAsFile().toPath());
                    mergeShellJars(tempPassThroughJar, r8Output.toFile(), outputJarFile);
                    getLogger().lifecycle("[Jiagu][R8] Runtime-only R8 完成；pass-through classes 未作为 R8 program input");
                } finally {
                    Files.deleteIfExists(runtimeRules);
                    Files.deleteIfExists(r8Output);
                }
            } else {
                mergeShellJars(tempPassThroughJar, tempRuntimeJar, outputJarFile);
            }
            finishStage(getRuntimeR8Enabled().get()
                    ? "Jiagu Runtime-only R8 classfile 优化/合流" : "Shell pass-through 合流", stageStartedAt, stageTimes);
        } catch (Exception e) {
            String message = "[Jiagu] 业务 DEX 转换与 Payload 生成失败";
            getLogger().error(message, e);
            throw new IOException(message, e);
        } finally {
            // 清理临时文件
            deleteDirectory(tempDexDir.toFile());
            tempBusinessJar.delete();
            tempPassThroughJar.delete();
            tempRuntimeJar.delete();
            tempCombinedShellJar.delete();
        }
        
        getLogger().lifecycle("[Jiagu] 业务代码加固阶段完成。输出: {}", outputJarFile.getName());
        long totalMs = elapsedMillis(taskStartedAt);
        getLogger().lifecycle("[Jiagu][计时] ===== 加固阶段耗时汇总 =====");
        for (Map.Entry<String, Long> entry : stageTimes.entrySet()) {
            getLogger().lifecycle("[Jiagu][计时] {}: {}", entry.getKey(), formatDuration(entry.getValue()));
        }
        getLogger().lifecycle("[Jiagu][计时] 总耗时: {}", formatDuration(totalMs));
    }

    private static void mergeShellJars(File passThroughJar, File runtimeJar, File outputJar) throws IOException {
        Files.createDirectories(outputJar.toPath().toAbsolutePath().getParent());
        Set<String> entries = new HashSet<>();
        try (JarOutputStream output = new JarOutputStream(new FileOutputStream(outputJar))) {
            for (File input : Arrays.asList(passThroughJar, runtimeJar)) {
                try (JarFile jar = new JarFile(input)) {
                    Enumeration<JarEntry> sourceEntries = jar.entries();
                    while (sourceEntries.hasMoreElements()) {
                        JarEntry entry = sourceEntries.nextElement();
                        if (entry.isDirectory()) continue;
                        if (!entries.add(entry.getName())) {
                            throw new IOException("[Jiagu] Duplicate Shell entry after Runtime R8: "
                                    + entry.getName() + " in " + input);
                        }
                        output.putNextEntry(new JarEntry(entry.getName()));
                        try (InputStream data = jar.getInputStream(entry)) {
                            data.transferTo(output);
                        }
                        output.closeEntry();
                    }
                }
            }
        }
    }

    private void runD8(File businessJar, File shellJar, File runtimeJar, Path output) throws Exception {
        D8Command command = D8Command.builder()
                .addProgramFiles(businessJar.toPath())
                .addClasspathFiles(shellJar.toPath(), runtimeJar.toPath())
                .addLibraryFiles(getBootClasspath().getFiles().stream()
                        .map(File::toPath).collect(java.util.stream.Collectors.toList()))
                .setOutput(output, OutputMode.DexIndexed)
                .setMinApiLevel(getMinApiLevel().get())
                .setMode(getDebuggable().get() ? CompilationMode.DEBUG : CompilationMode.RELEASE)
                .build();
        D8.run(command);
    }

    private List<InputArtifact> inspectInputArtifacts() throws IOException {
        List<InputArtifact> artifacts = new ArrayList<>();
        for (RegularFile jar : getAllJars().get()) {
            Path path = jar.getAsFile().toPath();
            artifacts.add(new InputArtifact(path, "JAR", R8ArtifactDetector.inspectJar(path), hashArtifact(path),
                    countRuntimeClasses(path, false)));
        }
        for (Directory directory : getAllDirectories().get()) {
            Path path = directory.getAsFile().toPath();
            artifacts.add(new InputArtifact(path, "DIRECTORY",
                    R8ArtifactDetector.inspectDirectory(path), hashArtifact(path), countRuntimeClasses(path, true)));
        }
        return artifacts;
    }

    private static int countRuntimeClasses(Path artifact, boolean directory) throws IOException {
        int count = 0;
        if (directory) {
            try (java.util.stream.Stream<Path> paths = Files.walk(artifact)) {
                for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                    String name = artifact.relativize(path).toString().replace('\\', '/');
                    if (isJiaguRuntimeClassEntry(name)) count++;
                }
            }
        } else {
            try (JarFile jar = new JarFile(artifact.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    if (isJiaguRuntimeClassEntry(entries.nextElement().getName())) count++;
                }
            }
        }
        return count;
    }

    private void writeInputIndex(List<InputArtifact> artifacts) throws IOException {
        Path output = getInputIndexFile().get().getAsFile().toPath();
        Files.createDirectories(output.toAbsolutePath().getParent());
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"schemaVersion\": 4,\n  \"variantMinifyEnabled\": ")
                .append(getMinifyEnabled().get())
                .append(",\n  \"runtimeR8Enabled\": ").append(getRuntimeR8Enabled().get())
                .append(",\n  \"runtimeR8ProgramNamespace\": \"io.github.xjc.jiagu.local.**\"")
                .append(",\n  \"nonRuntimeR8ProgramInputCount\": 0,\n  \"artifacts\": [\n");
        for (int i = 0; i < artifacts.size(); i++) {
            InputArtifact artifact = artifacts.get(i);
            json.append("    {\"path\": \"").append(jsonEscape(artifact.path.toString()))
                    .append("\", \"type\": \"").append(artifact.type)
                    .append("\", \"sha256\": \"").append(artifact.sha256)
                    .append("\", \"classification\": \"").append(artifact.result.classification)
                    .append("\", \"classCount\": ").append(artifact.result.classCount)
                    .append(", \"markedClassCount\": ").append(artifact.result.markedClassCount)
                    .append(", \"evidence\": \"").append(jsonEscape(artifact.result.evidence))
                    .append("\", \"action\": \"")
                    .append(artifact.action(getRuntimeR8Enabled().get()))
                    .append("\", \"runtimeR8ClassCount\": ").append(artifact.runtimeClassCount)
                    .append(", \"r8ProgramInput\": ")
                    .append(getRuntimeR8Enabled().get() && artifact.runtimeClassCount > 0)
                    .append('}');
            if (i + 1 < artifacts.size()) json.append(',');
            json.append('\n');
        }
        json.append("  ]\n}\n");
        Files.write(output, json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String hashArtifact(Path artifact) throws IOException {
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        if (Files.isDirectory(artifact)) {
            try (java.util.stream.Stream<Path> paths = Files.walk(artifact)) {
                for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile).sorted()::iterator) {
                    digest.update(artifact.relativize(path).toString().replace('\\', '/')
                            .getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    updateDigest(digest, path);
                }
            }
        } else {
            updateDigest(digest, artifact);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return value.toString();
    }

    private static void updateDigest(java.security.MessageDigest digest, Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void verifyNoDuplicateDexClasses(File[] dexFiles) throws IOException {
        Map<String, String> definitions = new LinkedHashMap<>();
        for (File dexFile : dexFiles) {
            for (String className : VerifyServicesTask.classNames(Files.readAllBytes(dexFile.toPath()))) {
                String previous = definitions.putIfAbsent(className, dexFile.getName());
                if (previous != null) {
                    throw new IOException("[Jiagu] Payload class descriptor is defined twice: "
                            + className + " in " + previous + " and " + dexFile.getName());
                }
            }
        }
    }

    private static final class InputArtifact {
        final Path path;
        final String type;
        final R8ArtifactDetector.Result result;
        final String sha256;
        final int runtimeClassCount;

        InputArtifact(Path path, String type, R8ArtifactDetector.Result result, String sha256,
                      int runtimeClassCount) {
            this.path = path.toAbsolutePath();
            this.type = type;
            this.result = result;
            this.sha256 = sha256;
            this.runtimeClassCount = runtimeClassCount;
        }

        String action(boolean runtimeR8Enabled) {
            return runtimeR8Enabled && runtimeClassCount > 0
                    ? "JIAGU_RUNTIME_R8_CLASSFILE_THEN_AGP_D8"
                    : "D8_PASSTHROUGH_OR_PAYLOAD_ROUTING";
        }
    }

    private static final class SeenEntry {
        final String origin;
        final String sha256;

        SeenEntry(String origin, String sha256) {
            this.origin = origin;
            this.sha256 = sha256;
        }
    }

    private void processEntry(JarOutputStream shellJos, JarOutputStream businessJos,
                              String name, byte[] data, Map<String, SeenEntry> processedNames,
                              ServiceDescriptors services, String origin, int[] businessClassCount,
                              String payloadMode, Set<String> payloadPackages, Set<String> shellPackages,
                              Set<String> shellClasses, Set<String> startupClasses, String startupPolicy,
                              JarOutputStream runtimeJos)
            throws IOException {
        if (!androidXFactory && name.equals("io/github/xjc/jiagu/local/LocalAndroidXComponentFactory.class")) return;
        // Merge all providers before duplicate-entry filtering; multiple libraries may
        // contribute implementations of the same SPI.
        if (name.startsWith(ServiceDescriptors.PREFIX)) {
            services.add(name, data);
            return;
        }
        boolean programClass = isProgramClassEntry(name);
        SeenEntry previous = processedNames.get(name);
        if (previous != null) {
            if (programClass) {
                String digest = LocalBuildHash.sha256(data);
                if (!previous.sha256.equals(digest)) {
                    throw new IOException("[Jiagu] Duplicate class with different bytecode: " + name
                            + "\n  first: " + previous.origin
                            + "\n  second: " + origin);
                }
            }
            return;
        }

        PayloadRouting.Decision routing = PayloadRouting.route(name, payloadMode, payloadPackages,
                shellPackages, shellClasses, startupClasses);
        boolean runtimeClass = programClass && isJiaguRuntimeClassEntry(name);
        if (runtimeClass && !origin.toLowerCase(Locale.ROOT).replace('\\', '/').contains("jiagu-local-runtime")) {
            throw new IOException("[Jiagu] Reserved Runtime namespace is occupied by a non-Runtime artifact: "
                    + name + " from " + origin);
        }
        boolean shouldKeepInShell = routing.destination == PayloadRouting.Destination.SHELL
                || shouldKeepInShell(name, configuredUploaderClass());
        if (routing.startupAllowlistConflict) {
            String message = "[Jiagu][Routing] Manifest startup class forced into Shell despite "
                    + "payloadIncludePackages: " + name;
            if ("fail".equals(startupPolicy)) throw new IOException(message);
            if (!"off".equals(startupPolicy)) getLogger().warn(message);
        }
        if (programClass && !shouldKeepInShell && getMinifyEnabled().get()
                && !getRuntimeR8Enabled().get()) {
            throw new IOException("[Jiagu] Variant enables minifyEnabled=true but Payload class " + name
                    + " would bypass the business R8 configuration. Set payloadR8Policy='fail' and "
                    + "move it to Shell, or use a future preprocessed/R8 Payload pipeline.");
        }

        if (shouldKeepInShell || !programClass) {
            // Jiagu Runtime classfiles receive their isolated R8 pass; all other Shell
            // program classes and resources are copied unchanged into the pass-through lane.
            JarOutputStream shellOutput = runtimeClass ? runtimeJos : shellJos;
            JarEntry outEntry = new JarEntry(name);
            shellOutput.putNextEntry(outEntry);
            shellOutput.write(data);
            shellOutput.closeEntry();
        } else {
            // 业务 Class 文件：放入业务 JAR，后续统一转换 DEX 并加密
            JarEntry outEntry = new JarEntry(name);
            businessJos.putNextEntry(outEntry);
            businessJos.write(data);
            businessJos.closeEntry();
            businessClassCount[0]++;
        }
        processedNames.put(name, new SeenEntry(origin,
                programClass ? LocalBuildHash.sha256(data) : ""));
    }

    private static boolean isProgramClassEntry(String name) {
        return name.endsWith(".class")
                && !name.startsWith("META-INF/")
                && !name.equals("module-info.class");
    }

    private static boolean isGeneratedRuntimeNamespaceClass(String name) {
        return name.matches("io/github/xjc/jiagu/local/R(?:\\$[^/]+)?\\.class")
                || name.equals("io/github/xjc/jiagu/local/BuildConfig.class");
    }

    private static boolean isJiaguRuntimeClassEntry(String name) {
        return name.startsWith("io/github/xjc/jiagu/local/") && name.endsWith(".class")
                && !isGeneratedRuntimeNamespaceClass(name);
    }

    static boolean shouldKeepInShell(String name) { return PayloadRouting.isFixedShell(name); }

    static boolean shouldKeepInShell(String name, String uploaderClass) {
        if (shouldKeepInShell(name)) {
            return true;
        }
        if (uploaderClass == null || uploaderClass.trim().isEmpty()) {
            return false;
        }
        String classPath = uploaderClass.trim().replace('.', '/');
        return name.equals(classPath + ".class")
                || name.startsWith(classPath + "$" );
    }

    private String configuredUploaderClass() {
        if (!getStartupLogUploaderClass().isPresent()) {
            return null;
        }
        String value = getStartupLogUploaderClass().get().trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            throw new IllegalArgumentException("Invalid startupLogUploaderClass: " + value);
        }
        return value;
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
            values.add(LocalBuildHash.sha256(entry.getValue()));
        }
        return LocalBuildHash.sha256(canonical(values.toArray(new String[0])).getBytes(StandardCharsets.UTF_8));
    }

    private static String canonical(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) result.append(value.getBytes(StandardCharsets.UTF_8).length)
                .append(':').append(value).append('\n');
        return result.toString();
    }

    private static final class EntryValue {
        final String path;
        final byte[] data;
        EntryValue(String path, byte[] data) { this.path = path; this.data = data; }
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
