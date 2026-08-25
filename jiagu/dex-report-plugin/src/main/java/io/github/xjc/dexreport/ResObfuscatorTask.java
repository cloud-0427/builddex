package io.github.xjc.dexreport;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A Gradle task that obfuscates resource paths and keys in an Android resource package (.ap_).
 * It renames files in res/, updates the Global String Pool, and obfuscates the Key String Pool.
 */
public abstract class ResObfuscatorTask extends DefaultTask {

    @InputFile
    public abstract RegularFileProperty getInputResourcePackage();

    @OutputFile
    public abstract RegularFileProperty getOutputResourcePackage();

    @org.gradle.api.tasks.Input
    @org.gradle.api.tasks.Optional
    public abstract org.gradle.api.provider.ListProperty<String> getResConfigs();

    @TaskAction
    public void run() throws IOException {
        System.out.println("[Jiagu] ResObfuscatorTask started execution.");
        File inputFile = getInputResourcePackage().get().getAsFile();
        File outputFile = getOutputResourcePackage().get().getAsFile();
        
        System.out.println("[Jiagu] Input ARSC package: " + inputFile.getAbsolutePath());
        if (getResConfigs().isPresent()) {
            System.out.println("[Jiagu] resConfigs to keep: " + getResConfigs().get());
        }

        Path tempDir = Files.createTempDirectory("res_obfuscator");
        try {
            // 1. Decompress the resource package
            unzip(inputFile, tempDir.toFile());

            // 2. Prune resources by language
            if (getResConfigs().isPresent() && !getResConfigs().get().isEmpty()) {
                pruneResources(tempDir.toFile(), getResConfigs().get());
            }

            // 3. Scan and Rename resource files
            Map<String, String> pathMap = new HashMap<>();
            obfuscateResources(tempDir.toFile(), pathMap);
            System.out.println("[Jiagu] Obfuscated " + pathMap.size() + " resource file paths.");

            // 4. Patch resources.arsc (Global String Pool + Key String Pools)
            File arscFile = new File(tempDir.toFile(), "resources.arsc");
            if (arscFile.exists()) {
                patchArsc(arscFile, pathMap);
            } else {
                System.out.println("[Jiagu] WARNING: resources.arsc not found in package!");
            }

            // 5. Re-compress back to .ap_
            zip(tempDir.toFile(), outputFile);
            System.out.println("[Jiagu] Re-packaged obfuscated resources successfully to: " + outputFile.getAbsolutePath());
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }

    private void unzip(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    newFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void zip(File sourceDir, File zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            Path sourcePath = sourceDir.toPath();
            Files.walk(sourcePath).forEach(path -> {
                if (Files.isDirectory(path)) return;
                String name = sourcePath.relativize(path).toString().replace('\\', '/');
                try {
                    ZipEntry entry = new ZipEntry(name);
                    
                    // resources.arsc must be STORED and uncompressed for Android 11+
                    if (name.equals("resources.arsc")) {
                        entry.setMethod(ZipEntry.STORED);
                        byte[] bytes = Files.readAllBytes(path);
                        entry.setSize(bytes.length);
                        entry.setCompressedSize(bytes.length);
                        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                        crc.update(bytes, 0, bytes.length);
                        entry.setCrc(crc.getValue());
                    }
                    
                    zos.putNextEntry(entry);
                    Files.copy(path, zos);
                    zos.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private void obfuscateResources(File workDir, Map<String, String> pathMap) throws IOException {
        File resRoot = new File(workDir, "res");
        if (!resRoot.exists()) return;

        File[] typeDirs = resRoot.listFiles(File::isDirectory);
        if (typeDirs == null) return;

        int folderCount = 0;
        for (File typeDir : typeDirs) {
            String typeName = typeDir.getName();
            String newFolderName = "r" + (folderCount++); // Using 'r' prefix for renamed folders
            File newTypeDir = new File(resRoot, newFolderName);
            newTypeDir.mkdirs();

            File[] files = typeDir.listFiles();
            if (files == null) continue;

            int fileCount = 0;
            for (File file : files) {
                if (file.isDirectory()) continue;
                String ext = "";
                int dotIdx = file.getName().lastIndexOf('.');
                if (dotIdx > 0) ext = file.getName().substring(dotIdx);
                
                String newFileName = generateObfuscatedName(fileCount++) + ext;
                String oldPath = "res/" + typeName + "/" + file.getName();
                String newPath = "res/" + newFolderName + "/" + newFileName;
                
                pathMap.put(oldPath, newPath);
                
                File destFile = new File(newTypeDir, newFileName);
                Files.move(file.toPath(), destFile.toPath());
            }
            // Cleanup old directory
            if (typeDir.exists()) {
                String[] content = typeDir.list();
                if (content != null && content.length == 0) {
                    typeDir.delete();
                }
            }
        }
    }

    private void patchArsc(File arscFile, Map<String, String> pathMap) throws IOException {
        System.out.println("[Jiagu] Deep obfuscating resources.arsc...");
        byte[] data = Files.readAllBytes(arscFile.toPath());
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // 1. Table Header
        short tableType = buffer.getShort();
        if (tableType != 0x0002) {
            System.out.println("[Jiagu] ERROR: Not a valid resources.arsc file (Type: " + String.format("0x%04X", tableType) + ")");
            return;
        }
        buffer.getShort(); // tableHeaderSize
        buffer.getInt(); // tableTotalSize
        int packageCount = buffer.getInt();
        
        // 2. Global String Pool
        StringPool globalPool = StringPool.read(buffer);
        
        // Obfuscate paths in global pool
        int pathObfuscatedCount = 0;
        for (int i = 0; i < globalPool.strings.size(); i++) {
            String s = globalPool.strings.get(i);
            if (pathMap.containsKey(s)) {
                globalPool.strings.set(i, pathMap.get(s));
                pathObfuscatedCount++;
            }
        }
        System.out.println("[Jiagu] Obfuscated " + pathObfuscatedCount + " paths in Global String Pool.");

        // 3. Packages
        List<PackageChunk> packages = new ArrayList<>();
        for (int i = 0; i < packageCount; i++) {
            packages.add(PackageChunk.read(buffer));
        }
        
        // Obfuscate keys in each package
        int totalKeyObfuscated = 0;
        for (PackageChunk pkg : packages) {
            int pkgKeyObfuscated = 0;
            int obfuscationIndex = 0;
            for (int i = 0; i < pkg.keyPool.strings.size(); i++) {
                String key = pkg.keyPool.strings.get(i);
                if (!isWhitelisted(key)) {
                    String newKey = generateObfuscatedName(obfuscationIndex++);
                    pkg.keyPool.strings.set(i, newKey);
                    pkgKeyObfuscated++;
                }
            }
            System.out.println("[Jiagu] Package '" + pkg.name + "': Obfuscated " + pkgKeyObfuscated + " keys.");
            totalKeyObfuscated += pkgKeyObfuscated;
        }
        System.out.println("[Jiagu] Total resource keys obfuscated: " + totalKeyObfuscated);

        // 4. Rebuild everything
        byte[] globalPoolData = globalPool.write();
        List<byte[]> packageDataList = new ArrayList<>();
        int allPackagesSize = 0;
        for (PackageChunk pkg : packages) {
            byte[] pkgData = pkg.write();
            packageDataList.add(pkgData);
            allPackagesSize += pkgData.length;
        }
        
        int newTotalSize = 12 + globalPoolData.length + allPackagesSize;
        ByteBuffer finalArsc = ByteBuffer.allocate(newTotalSize).order(ByteOrder.LITTLE_ENDIAN);
        finalArsc.putShort(tableType);
        finalArsc.putShort((short) 12); // Table header size is always 12
        finalArsc.putInt(newTotalSize);
        finalArsc.putInt(packageCount);
        finalArsc.put(globalPoolData);
        for (byte[] pkgData : packageDataList) {
            finalArsc.put(pkgData);
        }
        
        Files.write(arscFile.toPath(), finalArsc.array());
        System.out.println("[Jiagu] resources.arsc deep obfuscation complete. Final size: " + newTotalSize + " bytes.");
    }

    private boolean isWhitelisted(String key) {
        if (key.isEmpty()) return true;
        if (key.startsWith("android:")) return true;
        // Whitelist common keys that are often accessed by name or required by system
        return key.equals("app_name") || 
               key.equals("icon") || 
               key.equals("roundIcon") || 
               key.equals("ic_launcher") ||
               key.equals("ic_launcher_round");
    }

    private String generateObfuscatedName(int index) {
        int val = index;
        StringBuilder sb = new StringBuilder();
        while (val >= 0) {
            sb.insert(0, (char) ('a' + (val % 26)));
            val = (val / 26) - 1;
        }
        return sb.toString();
    }

    private void pruneResources(File workDir, List<String> allowedLocales) {
        File resRoot = new File(workDir, "res");
        if (!resRoot.exists()) return;

        File[] dirs = resRoot.listFiles(File::isDirectory);
        if (dirs == null) return;

        Set<String> keepLocales = new HashSet<>(allowedLocales);
        int prunedCount = 0;
        for (File dir : dirs) {
            String name = dir.getName();
            if (isLocaleFolder(name)) {
                boolean match = false;
                for (String locale : keepLocales) {
                    if (name.contains("-" + locale) || name.contains("-r" + locale.toUpperCase())) {
                        match = true;
                        break;
                    }
                }
                if (!match) {
                    deleteDirectory(dir);
                    prunedCount++;
                }
            }
        }
        System.out.println("[Jiagu] Pruned " + prunedCount + " localized resource directories.");
    }

    private boolean isLocaleFolder(String folderName) {
        String[] parts = folderName.split("-");
        for (String part : parts) {
            if (part.matches("[a-z]{2,3}")) return true; // Language code
            if (part.startsWith("r") && part.length() == 3 && part.substring(1).matches("[A-Z]{2}")) return true; // Region code
        }
        return false;
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteDirectory(file);
            }
        }
        dir.delete();
    }

    // --- Helper Classes for ARSC Parsing ---

    private static class StringPool {
        short headerSize;
        int flags;
        int stringCount;
        int styleCount;
        int stringStart;
        int stylesStart;
        List<String> strings;
        byte[] stylesData;
        boolean isUtf8;

        static StringPool read(ByteBuffer buffer) {
            int pos = buffer.position();
            short type = buffer.getShort();
            if (type != 0x0001) throw new RuntimeException("Expected StringPool chunk (0x0001), found: " + String.format("0x%04X", type));
            
            StringPool pool = new StringPool();
            pool.headerSize = buffer.getShort();
            int size = buffer.getInt();
            pool.stringCount = buffer.getInt();
            pool.styleCount = buffer.getInt();
            pool.flags = buffer.getInt();
            pool.stringStart = buffer.getInt();
            pool.stylesStart = buffer.getInt();
            pool.isUtf8 = (pool.flags & (1 << 8)) != 0;

            int[] offsets = new int[pool.stringCount];
            for (int i = 0; i < pool.stringCount; i++) offsets[i] = buffer.getInt();
            // Skip style offsets
            for (int i = 0; i < pool.styleCount; i++) buffer.getInt(); 

            pool.strings = new ArrayList<>(pool.stringCount);
            for (int i = 0; i < pool.stringCount; i++) {
                buffer.position(pos + pool.stringStart + offsets[i]);
                if (pool.isUtf8) {
                    readUtf8Len(buffer); // char len (ignored)
                    int byteLen = readUtf8Len(buffer);
                    byte[] data = new byte[byteLen];
                    buffer.get(data);
                    pool.strings.add(new String(data, StandardCharsets.UTF_8));
                } else {
                    int charLen = readUtf16Len(buffer);
                    byte[] data = new byte[charLen * 2];
                    buffer.get(data);
                    pool.strings.add(new String(data, StandardCharsets.UTF_16LE));
                }
            }

            if (pool.styleCount > 0 && pool.stylesStart != 0) {
                int styleDataSize = size - pool.stylesStart;
                pool.stylesData = new byte[styleDataSize];
                buffer.position(pos + pool.stylesStart);
                buffer.get(pool.stylesData);
            }

            buffer.position(pos + size);
            return pool;
        }

        byte[] write() {
            int headerSize = 28;
            ByteBuffer dataBuffer = ByteBuffer.allocate(strings.size() * 512 + 4096).order(ByteOrder.LITTLE_ENDIAN);
            int[] offsets = new int[strings.size()];
            
            for (int i = 0; i < strings.size(); i++) {
                offsets[i] = dataBuffer.position();
                String s = strings.get(i);
                byte[] data = isUtf8 ? s.getBytes(StandardCharsets.UTF_8) : s.getBytes(StandardCharsets.UTF_16LE);
                
                if (isUtf8) {
                    writeUtf8Len(dataBuffer, s.length()); // char len
                    writeUtf8Len(dataBuffer, data.length); // byte len
                    dataBuffer.put(data);
                    dataBuffer.put((byte) 0);
                } else {
                    writeUtf16Len(dataBuffer, s.length());
                    dataBuffer.put(data);
                    dataBuffer.putShort((short) 0);
                }
            }
            
            // 4-byte alignment
            while (dataBuffer.position() % 4 != 0) dataBuffer.put((byte) 0);
            int finalDataSize = dataBuffer.position();

            int newStringStart = headerSize + (strings.size() * 4) + (styleCount * 4);
            int newStylesStart = 0;
            int totalSize = newStringStart + finalDataSize;
            if (stylesData != null) {
                newStylesStart = totalSize;
                totalSize += stylesData.length;
            }

            ByteBuffer out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
            out.putShort((short) 0x0001);
            out.putShort((short) headerSize);
            out.putInt(totalSize);
            out.putInt(strings.size());
            out.putInt(styleCount);
            out.putInt(flags);
            out.putInt(newStringStart);
            out.putInt(newStylesStart);
            for (int offset : offsets) out.putInt(offset);
            for (int i = 0; i < styleCount; i++) out.putInt(0); // placeholder
            
            dataBuffer.flip();
            out.put(dataBuffer);
            if (stylesData != null) out.put(stylesData);

            return out.array();
        }

        private static int readUtf8Len(ByteBuffer buffer) {
            int val = buffer.get() & 0xFF;
            if ((val & 0x80) != 0) {
                val = ((val & 0x7F) << 8) | (buffer.get() & 0xFF);
            }
            return val;
        }

        private static void writeUtf8Len(ByteBuffer buffer, int len) {
            if (len > 0x7F) {
                buffer.put((byte) ((len >> 8) | 0x80));
                buffer.put((byte) (len & 0xFF));
            } else {
                buffer.put((byte) len);
            }
        }

        private static int readUtf16Len(ByteBuffer buffer) {
            int val = buffer.getShort() & 0xFFFF;
            if ((val & 0x8000) != 0) {
                val = ((val & 0x7FFF) << 16) | (buffer.getShort() & 0xFFFF);
            }
            return val;
        }

        private static void writeUtf16Len(ByteBuffer buffer, int len) {
            if (len > 0x7FFF) {
                buffer.putShort((short) ((len >> 16) | 0x8000));
                buffer.putShort((short) (len & 0xFFFF));
            } else {
                buffer.putShort((short) len);
            }
        }
    }

    private static class PackageChunk {
        int id;
        String name;
        StringPool typePool;
        StringPool keyPool;
        byte[] remainingData;

        static PackageChunk read(ByteBuffer buffer) {
            int pos = buffer.position();
            short type = buffer.getShort();
            if (type != 0x0200) throw new RuntimeException("Expected Package chunk (0x0200), found: " + String.format("0x%04X", type));
        buffer.getShort(); // headerSize
        int size = buffer.getInt();
        
        PackageChunk pkg = new PackageChunk();
        pkg.id = buffer.getInt();
        byte[] nameBytes = new byte[256];
        buffer.get(nameBytes);
        pkg.name = new String(nameBytes, StandardCharsets.UTF_16LE).trim();
        
        int typeStart = buffer.getInt();
        buffer.getInt(); // typeCount
        int keyStart = buffer.getInt();
        buffer.getInt(); // keyCount
            
            buffer.position(pos + typeStart);
            pkg.typePool = StringPool.read(buffer);
            
            buffer.position(pos + keyStart);
            pkg.keyPool = StringPool.read(buffer);
            
            int remainingStart = buffer.position();
            int remainingSize = size - (remainingStart - pos);
            pkg.remainingData = new byte[remainingSize];
            buffer.get(pkg.remainingData);
            
            buffer.position(pos + size);
            return pkg;
        }

        byte[] write() {
            byte[] typePoolData = typePool.write();
            byte[] keyPoolData = keyPool.write();
            
            int headerSize = 288;
            int keyStart = headerSize + typePoolData.length;
            int totalSize = keyStart + keyPoolData.length + remainingData.length;
            
            ByteBuffer out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
            out.putShort((short) 0x0200);
            out.putShort((short) headerSize);
            out.putInt(totalSize);
            out.putInt(id);
            
            byte[] nameBytes = new byte[256];
            byte[] actualName = name.getBytes(StandardCharsets.UTF_16LE);
            System.arraycopy(actualName, 0, nameBytes, 0, Math.min(actualName.length, 256));
            out.put(nameBytes);
            
            out.putInt(headerSize);
            out.putInt(typePool.strings.size());
            out.putInt(keyStart);
            out.putInt(keyPool.strings.size());
            
            // Pad the rest of the header if needed (up to 288)
            while (out.position() < headerSize) out.put((byte) 0);
            
            out.position(headerSize);
            out.put(typePoolData);
            out.put(keyPoolData);
            out.put(remainingData);
            
            return out.array();
        }
    }
}
