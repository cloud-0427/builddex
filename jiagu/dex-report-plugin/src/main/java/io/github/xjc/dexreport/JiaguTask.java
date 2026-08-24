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
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;

import org.gradle.api.tasks.Internal;
import java.security.*;
import java.util.Base64;
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

    @Internal
    public abstract Property<String> getPrivateKeyForManifest();

    @InputFiles
    public abstract ListProperty<RegularFile> getAllJars();

    @InputFiles
    public abstract ListProperty<Directory> getAllDirectories();

    @Internal
    public abstract RegularFileProperty getKeysFile();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @OutputDirectory
    public abstract DirectoryProperty getOutAssetsDir();

    @TaskAction
    public void execute() throws IOException {
        int versionCode = getVersionCode().get();
        // 1. 获取或生成 Session Key
        byte[] sessionKey = handleKeyManagement(versionCode);
        
        File outputJarFile = getOutputJar().get().getAsFile();
        File assetsDir = getOutAssetsDir().get().getAsFile();
        if (!assetsDir.exists()) assetsDir.mkdirs();
        File payloadFile = new File(assetsDir, "jiagu_data.bin");

        // ... 省略部分中间 JAR 处理逻辑 (与之前相同) ...

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
                        byte[] encryptedDex = encrypt(dexData, sessionKey);
                        
                        // 写入当前加密 DEX 的长度 (4 bytes)
                        fos.write(intToBytes(encryptedDex.length));
                        // 写入加密 DEX 数据
                        fos.write(encryptedDex);
                        
                        getLogger().lifecycle("[Jiagu] 已使用 AES-GCM 加密并打包: {} ({} bytes)", dexFile.getName(), encryptedDex.length);
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

    private byte[] handleKeyManagement(int versionCode) {
        File keyFile = getKeysFile().get().getAsFile();
        String jsonKey = getPublicKeyJsonKey().getOrElse("akmKeys");
        byte[] masterKey = "PRO_JIAGU_MASTER_KEY_2026_SECRET".getBytes(StandardCharsets.UTF_8);

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

                        getPrivateKeyForManifest().set(Base64.getEncoder().encodeToString(sessionKey));
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
            
            getPrivateKeyForManifest().set(Base64.getEncoder().encodeToString(sessionKey));
            
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
