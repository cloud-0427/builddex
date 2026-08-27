package io.github.xjc.dexreport;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * DEX 报告任务，适配 Java 1.8。
 */
@org.gradle.work.DisableCachingByDefault(because = "Diagnostic report task writes invocation-specific output")
public abstract class DexReportTask extends DefaultTask {

    @Input
    public abstract Property<String> getVariantName();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getApkDirectory();

    @Internal
    public abstract DirectoryProperty getAppBuildDirectory();

    @TaskAction
    public void reportDexFiles() throws IOException {
        String variantName = getVariantName().get();
        File buildDirectory = getAppBuildDirectory().get().getAsFile();
        File intermediateDexDirectory = new File(
                buildDirectory,
                "intermediates/dex/" + variantName
        );

        List<File> intermediateDexFiles = findFiles(intermediateDexDirectory, ".dex");

        getLogger().lifecycle("");
        getLogger().lifecycle("========== DEX Report: {} ==========", variantName);
        getLogger().lifecycle("临时 DEX 根目录: {}", intermediateDexDirectory.getAbsolutePath());
        getLogger().lifecycle("临时 DEX 数量: {}", intermediateDexFiles.size());
        for (File dexFile : intermediateDexFiles) {
            getLogger().lifecycle("  - {} ({} bytes)", dexFile.getAbsolutePath(), dexFile.length());
        }

        List<File> apkFiles = findFiles(getApkDirectory().get().getAsFile(), ".apk");
        getLogger().lifecycle("APK 输出目录: {}", getApkDirectory().get().getAsFile().getAbsolutePath());

        for (File apkFile : apkFiles) {
            List<String> packagedDexFiles = dexEntries(apkFile);
            getLogger().lifecycle("APK: {}", apkFile.getAbsolutePath());
            getLogger().lifecycle("APK 内最终 DEX 数量: {}", packagedDexFiles.size());
            for (String dexEntry : packagedDexFiles) {
                getLogger().lifecycle("  - {}", dexEntry);
            }
        }
        getLogger().lifecycle("========================================");
        getLogger().lifecycle("");
    }

    private static List<File> findFiles(File root, String extension) {
        List<File> result = new ArrayList<>();
        if (!root.isDirectory()) {
            return result;
        }

        File[] children = root.listFiles();
        if (children == null) {
            return result;
        }

        for (File child : children) {
            if (child.isDirectory()) {
                result.addAll(findFiles(child, extension));
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(extension)) {
                result.add(child);
            }
        }

        result.sort(Comparator.comparing(File::getAbsolutePath));
        return result;
    }

    private static List<String> dexEntries(File apkFile) throws IOException {
        List<String> result = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.matches("classes(\\d*)?\\.dex")) {
                    result.add(name);
                }
            }
        }
        result.sort(String::compareTo);
        return result;
    }
}
