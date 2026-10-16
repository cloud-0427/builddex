package io.github.xjc.jiagu.local;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import dalvik.system.InMemoryDexClassLoader;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.*;

final class LocalLoaderRegistry {
    private static ClassLoader original, loaded;
    private static String source;
    private static LocalPayload.Decoded retained;
    private static boolean loading;
    private static Throwable failed;
    private static String baseNativePath;
    private LocalLoaderRegistry() {}

    static synchronized ClassLoader load(ClassLoader parent, ApplicationInfo info) {
        if (loaded != null) {
            if (parent == loaded || (parent == original && info.sourceDir.equals(source))) return loaded;
            throw new IllegalStateException(LocalStartupResultCode.LOCAL_APK_IDENTITY_CHANGED.getWireName());
        }
        if (loading) throw new IllegalStateException(LocalStartupResultCode.LOCAL_BOOTSTRAP_REENTRANT.getWireName());
        if (failed != null) throw new IllegalStateException(LocalStartupResultCode.LOCAL_BOOTSTRAP_FAILED.getWireName(), failed);
        loading = true; original = parent; source = info.sourceDir;
        try { LocalEventReporter.bootstrap(info); } catch (Throwable ignored) { }
        long started = SystemClock.elapsedRealtime();
        LocalEvents.emit(LocalStartupStage.STARTUP_ATTEMPT, LocalStartupStatus.STARTED, LocalStartupResultCode.NONE, 0);
        LocalStartupStage stage = LocalStartupStage.LOCAL_PAYLOAD_READ;
        boolean decoderOwnsFailure = false;
        long stageStarted = started;
        try {
            LocalEvents.checkpoint(stage, LocalStartupCheckpoint.ENTER);
            byte[] asset = read(info.sourceDir);
            long readDuration = SystemClock.elapsedRealtime() - started;
            decoderOwnsFailure = true;
            retained = LocalPayload.decode(asset, info.packageName, new LocalPayload.Observer() {
                public void terminal(LocalStartupStage s, LocalStartupStatus status, LocalStartupResultCode code, long duration) {
                    LocalEvents.emit(s, status, code, duration + (s == LocalStartupStage.LOCAL_PAYLOAD_READ ? readDuration : 0));
                }
                public void checkpoint(LocalStartupStage s) { LocalEvents.checkpoint(s, LocalStartupCheckpoint.ENTER); }
            });
            decoderOwnsFailure = false;
            LocalEvents.metadata(info.packageName, retained.versionCode);
            stage = LocalStartupStage.BUSINESS_CLASSLOADER_CREATE; stageStarted = SystemClock.elapsedRealtime();
            LocalEvents.checkpoint(stage, LocalStartupCheckpoint.NATIVE_PATHS);
            String paths = nativePaths(info);
            LocalEvents.checkpoint(stage, LocalStartupCheckpoint.CONSTRUCT);
            loaded = new InMemoryDexClassLoader(retained.dex, paths, parent);
            LocalEvents.emit(stage, LocalStartupStatus.SUCCEEDED, LocalStartupResultCode.CLASSLOADER_CREATED, SystemClock.elapsedRealtime() - stageStarted);
            return loaded;
        } catch (Throwable error) {
            failed = error;
            if (!decoderOwnsFailure) LocalEvents.emit(stage, LocalStartupStatus.FAILED, LocalPayload.failureCode(error, stage),
                    SystemClock.elapsedRealtime() - stageStarted);
            throw new IllegalStateException(LocalStartupResultCode.LOCAL_BOOTSTRAP_FAILED.getWireName(), error);
        } finally { loading = false; }
    }

    private static byte[] read(String apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = null;
            Set<String> nativeAbis = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                collectAbi(e.getName(), nativeAbis);
                if ("assets/jiagu/local-payload.jgl".equals(e.getName())) {
                    if (entry != null) throw new LocalPayload.PayloadIOException(LocalStartupResultCode.LOCAL_PAYLOAD_DUPLICATE); entry = e;
                }
            }
            baseNativePath = selectNativePath(apk, nativeAbis);
            if (entry == null) throw new LocalPayload.PayloadIOException(LocalStartupResultCode.LOCAL_PAYLOAD_MISSING);
            if (entry.isDirectory() || entry.getSize() > LocalPayload.MAX_ASSET ||
                    (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED))
                throw new LocalPayload.PayloadIOException(LocalStartupResultCode.LOCAL_PAYLOAD_LENGTH_INVALID);
            try (InputStream input = zip.getInputStream(entry)) {
                if (entry.getSize() >= 0) return readExactly(input, (int) entry.getSize());
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192]; int n;
                while ((n = input.read(chunk)) != -1) {
                    if ((long) output.size() + n > LocalPayload.MAX_ASSET) throw new LocalPayload.PayloadIOException(LocalStartupResultCode.LOCAL_PAYLOAD_LENGTH_INVALID);
                    output.write(chunk, 0, n);
                }
                return output.toByteArray();
                }
            }
        }
    }

    static byte[] readExactly(InputStream input, int length) throws IOException {
        if (length < 0 || length > LocalPayload.MAX_ASSET) throw new IOException("PAYLOAD_LENGTH_INVALID");
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) throw new EOFException("PAYLOAD_TRUNCATED");
            if (count == 0) {
                int value = input.read();
                if (value < 0) throw new EOFException("PAYLOAD_TRUNCATED");
                bytes[offset++] = (byte) value;
            } else offset += count;
        }
        if (input.read() != -1) throw new IOException("PAYLOAD_LENGTH_MISMATCH");
        return bytes;
    }

    private static void collectAbi(String name, Set<String> found) {
        if (!name.startsWith("lib/")) return;
        int slash = name.indexOf('/', 4);
        if (slash > 4) found.add(name.substring(4, slash));
    }

    private static String selectNativePath(String apk, Set<String> found) {
        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        for (String abi : abis) if (found.contains(abi)) return apk + "!/lib/" + abi;
        return null;
    }

    private static String nativePaths(ApplicationInfo info) throws IOException {
        List<String> paths = new ArrayList<>();
        if (info.nativeLibraryDir != null) paths.add(info.nativeLibraryDir);
        // Include configuration splits: uncompressed JNI libraries may live in the ABI split.
        List<String> apks = new ArrayList<>();
        if (baseNativePath != null) paths.add(baseNativePath);
        if (info.splitSourceDirs != null) Collections.addAll(apks, info.splitSourceDirs);
        for (String apk : apks) try (ZipFile zip = new ZipFile(apk)) {
            Set<String> found = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) collectAbi(entries.nextElement().getName(), found);
            String path = selectNativePath(apk, found);
            if (path != null) paths.add(path);
        }
        return String.join(File.pathSeparator, paths);
    }
}
