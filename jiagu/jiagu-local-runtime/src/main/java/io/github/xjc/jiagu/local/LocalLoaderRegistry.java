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
    private LocalLoaderRegistry() {}

    static synchronized ClassLoader load(ClassLoader parent, ApplicationInfo info) {
        if (loaded != null) {
            if (parent == loaded || (parent == original && info.sourceDir.equals(source))) return loaded;
            throw new IllegalStateException("LOCAL_APK_IDENTITY_CHANGED");
        }
        if (loading) throw new IllegalStateException("LOCAL_BOOTSTRAP_REENTRANT");
        if (failed != null) throw new IllegalStateException("LOCAL_BOOTSTRAP_FAILED", failed);
        loading = true; original = parent; source = info.sourceDir;
        long started = SystemClock.elapsedRealtime();
        LocalEvents.emit("STARTUP_ATTEMPT", "STARTED", "", 0);
        String stage = "LOCAL_PAYLOAD_READ";
        boolean decoderOwnsFailure = false;
        long stageStarted = started;
        try {
            byte[] asset = read(info.sourceDir);
            long readDuration = SystemClock.elapsedRealtime() - started;
            decoderOwnsFailure = true;
            retained = LocalPayload.decode(asset, info.packageName, (s, status, code, duration) ->
                    LocalEvents.emit(s, status, code, duration + ("LOCAL_PAYLOAD_READ".equals(s) ? readDuration : 0)));
            decoderOwnsFailure = false;
            LocalEvents.metadata(info.packageName, retained.versionCode);
            stage = "BUSINESS_CLASSLOADER_CREATE"; stageStarted = SystemClock.elapsedRealtime();
            loaded = new InMemoryDexClassLoader(retained.dex, nativePaths(info), parent);
            LocalEvents.emit(stage, "SUCCEEDED", "CLASSLOADER_CREATED", SystemClock.elapsedRealtime() - stageStarted);
            return loaded;
        } catch (Throwable error) {
            failed = error;
            if (!decoderOwnsFailure) LocalEvents.emit(stage, "FAILED", LocalPayload.failureCode(error, stage),
                    SystemClock.elapsedRealtime() - stageStarted);
            throw new IllegalStateException("LOCAL_BOOTSTRAP_FAILED", error);
        } finally { loading = false; }
    }

    private static byte[] read(String apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if ("assets/jiagu/local-payload.jgl".equals(e.getName())) {
                    if (entry != null) throw new IOException("LOCAL_PAYLOAD_DUPLICATE"); entry = e;
                }
            }
            if (entry == null) throw new IOException("LOCAL_PAYLOAD_MISSING");
            if (entry.isDirectory() || entry.getSize() > LocalPayload.MAX_ASSET ||
                    (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED))
                throw new IOException("LOCAL_PAYLOAD_LENGTH_INVALID");
            try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192]; int n;
                while ((n = input.read(chunk)) != -1) {
                    if ((long) output.size() + n > LocalPayload.MAX_ASSET) throw new IOException("LOCAL_PAYLOAD_LENGTH_INVALID");
                    output.write(chunk, 0, n);
                }
                return output.toByteArray();
            }
        }
    }

    private static String nativePaths(ApplicationInfo info) throws IOException {
        List<String> paths = new ArrayList<>();
        if (info.nativeLibraryDir != null) paths.add(info.nativeLibraryDir);
        // MVP only supports base APK. Select a packaged ABI matching the actual process bitness.
        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        try (ZipFile zip = new ZipFile(info.sourceDir)) {
            for (String abi : abis) {
                boolean found = Collections.list(zip.entries()).stream().anyMatch(e -> e.getName().startsWith("lib/" + abi + "/"));
                if (found) { paths.add(info.sourceDir + "!/lib/" + abi); break; }
            }
        }
        return String.join(File.pathSeparator, paths);
    }
}
