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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.origin.Origin;

/**
 * 核心加固打包任务：
 * 负责遍历所有的 Class 文件，将壳代码放入输出 Jar，将业务代码加密。
 */
public abstract class JiaguTask extends DefaultTask {

    @Input
    public abstract Property<String> getAesKey();

    @InputFiles
    public abstract ListProperty<RegularFile> getAllJars();

    @InputFiles
    public abstract ListProperty<Directory> getAllDirectories();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @OutputDirectory
    public abstract DirectoryProperty getOutAssetsDir();

    @TaskAction
    public void execute() throws IOException {
        String key = getAesKey().get();
        File outputJarFile = getOutputJar().get().getAsFile();
        File assetsDir = getOutAssetsDir().get().getAsFile();
        if (!assetsDir.exists()) assetsDir.mkdirs();
        File payloadFile = new File(assetsDir, "jiagu_data.bin");

        Set<String> processedNames = new HashSet<>();
        File tempBusinessJar = File.createTempFile("business", ".jar");
        tempBusinessJar.deleteOnExit();

        getLogger().lifecycle("[Jiagu] 正在执行全量代码扫描与分离...");

        try (JarOutputStream shellJos = new JarOutputStream(new FileOutputStream(outputJarFile));
             JarOutputStream businessJos = new JarOutputStream(new FileOutputStream(tempBusinessJar))) {

            // 1. 处理所有输入的 JAR 文件（包括依赖库）
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

            // 2. 处理所有目录（当前项目的编译产物）
            for (Directory dir : getAllDirectories().get()) {
                File dirFile = dir.getAsFile();
                Files.walk(dirFile.toPath())
                        .filter(Files::isRegularFile)
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

        // 3. 将业务代码 JAR 转换为 DEX
        getLogger().lifecycle("[Jiagu] 正在将业务代码转换为 DEX...");
        Path tempDexDir = Files.createTempDirectory("jiagu_dex");
        try {
            D8Command command = D8Command.builder()
                    .addProgramFiles(tempBusinessJar.toPath())
                    .setOutput(tempDexDir, OutputMode.DexIndexed)
                    .setMinApiLevel(29)
                    .build();
            D8.run(command);

            File[] dexFiles = tempDexDir.toFile().listFiles((dir, name) -> name.endsWith(".dex"));
            if (dexFiles != null && dexFiles.length > 0) {
                // 按照文件名排序，确保 classes.dex, classes2.dex 等顺序一致
                Arrays.sort(dexFiles, Comparator.comparing(File::getName));
                
                try (FileOutputStream fos = new FileOutputStream(payloadFile)) {
                    // 写入 DEX 文件数量 (4 bytes)
                    fos.write(intToBytes(dexFiles.length));
                    
                    for (File dexFile : dexFiles) {
                        byte[] dexData = Files.readAllBytes(dexFile.toPath());
                        byte[] encryptedDex = encrypt(dexData, key);
                        
                        // 写入当前加密 DEX 的长度 (4 bytes)
                        fos.write(intToBytes(encryptedDex.length));
                        // 写入加密 DEX 数据
                        fos.write(encryptedDex);
                        
                        getLogger().lifecycle("[Jiagu] 已加密并打包: {} ({} bytes)", dexFile.getName(), encryptedDex.length);
                    }
                }
            } else {
                throw new IOException("D8 failed to produce any DEX files");
            }
        } catch (Exception e) {
            getLogger().error("[Jiagu] DEX 转换失败", e);
            throw new IOException(e);
        } finally {
            // 清理临时文件
            deleteDirectory(tempDexDir.toFile());
            tempBusinessJar.delete();
        }
        
        getLogger().lifecycle("[Jiagu] 任务完成。输出: {}, 加密包: {}", 
                outputJarFile.getName(), payloadFile.getName());
    }

    private void processEntry(JarOutputStream shellJos, JarOutputStream businessJos, String name, byte[] data, Set<String> processedNames) throws IOException {
        if (processedNames.contains(name)) {
            return;
        }

        // 必须保留在主 DEX (壳) 中的包名白名单
        // 1. 壳程序自身代码
        // 2. Androidx Startup (组件初始化框架)
        // 3. Androidx Core (包含 ComponentFactory 等系统底层回调)
        // 4. Androidx Lifecycle (某些初始化器依赖于此)
        // 5. Androidx Multidex (如果使用了的话)
        boolean shouldKeepInShell = name.startsWith("io/github/xjc/jiagu/") ||
                                   name.contains("/R$") || name.endsWith("/R.class") ||
                                   name.startsWith("androidx/startup/") ||
                                   name.startsWith("androidx/core/") ||
                                   name.startsWith("androidx/lifecycle/") ||
                                   name.startsWith("androidx/multidex/");

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

    private byte[] encrypt(byte[] data, String key) {
        byte[] keyBytes = key.getBytes();
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ keyBytes[i % keyBytes.length]);
        }
        return result;
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
