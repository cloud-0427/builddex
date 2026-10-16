package io.github.xjc.jiagu.local;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import androidx.core.app.CoreComponentFactory;

/** Inherits AndroidX's component wrapping, including instantiateApplication. */
public final class LocalAndroidXComponentFactory extends CoreComponentFactory {
    @Override public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo info) {
        return LocalLoaderRegistry.load(cl, info);
    }
    @Override public Application instantiateApplication(ClassLoader cl, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            LocalEvents.checkpoint(LocalStartupStage.REAL_APPLICATION_CREATE, LocalStartupCheckpoint.ENTER);
            Application app = super.instantiateApplication(cl, name);
            LocalEvents.emit(LocalStartupStage.REAL_APPLICATION_CREATE, LocalStartupStatus.SUCCEEDED, LocalStartupResultCode.APPLICATION_CREATED, android.os.SystemClock.elapsedRealtime() - started);
            return app;
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | RuntimeException | Error error) {
            LocalEvents.emit(LocalStartupStage.REAL_APPLICATION_CREATE, LocalStartupStatus.FAILED, LocalStartupResultCode.LOCAL_APPLICATION_CREATE_FAILED, android.os.SystemClock.elapsedRealtime() - started);
            throw error;
        }
    }
}
