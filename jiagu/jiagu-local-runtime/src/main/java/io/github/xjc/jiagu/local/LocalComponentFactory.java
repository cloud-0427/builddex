package io.github.xjc.jiagu.local;

import android.app.*;
import android.content.pm.ApplicationInfo;

public class LocalComponentFactory extends AppComponentFactory {
    @Override public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo info) {
        return LocalLoaderRegistry.load(cl, info);
    }
    @Override public Application instantiateApplication(ClassLoader cl, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            Application app = super.instantiateApplication(cl, name);
            LocalEvents.emit("REAL_APPLICATION_CREATE", "SUCCEEDED", "APPLICATION_CREATED", android.os.SystemClock.elapsedRealtime() - started);
            return app;
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | RuntimeException | Error error) {
            LocalEvents.emit("REAL_APPLICATION_CREATE", "FAILED", "LOCAL_APPLICATION_CREATE_FAILED", android.os.SystemClock.elapsedRealtime() - started);
            throw error;
        }
    }
}
