package lows.dgeon.ightr.jiagu;

import android.content.Context;
import io.github.xjc.jiagu.local.LocalStartupDiagnosticCode;
import android.util.Log;

public class Application extends android.app.Application {

    private static final String Tag = "Application";

    @Override
    public void onCreate() {
        io.github.xjc.jiagu.local.LocalEventReporter.applicationCheckpoint(LocalStartupDiagnosticCode.APPLICATION_ON_CREATE_ENTER);
        try {
            super.onCreate();
            Log.i(Tag, "Myapp, onCreate !!!");
            io.github.xjc.jiagu.local.LocalEventReporter.applicationCheckpoint(LocalStartupDiagnosticCode.APPLICATION_ON_CREATE_COMPLETED);
        } catch (RuntimeException | Error failure) {
            io.github.xjc.jiagu.local.LocalEventReporter.applicationCheckpoint(LocalStartupDiagnosticCode.APPLICATION_ON_CREATE_FAILED);
            throw failure;
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        io.github.xjc.jiagu.local.LocalEventReporter.applicationCheckpoint(LocalStartupDiagnosticCode.APPLICATION_ATTACH_ENTER);
        try {
            super.attachBaseContext(base);
            Log.i(Tag, "Myapp, attachBaseContext !!!");
            io.github.xjc.jiagu.local.LocalEventReporter.applicationCheckpoint(LocalStartupDiagnosticCode.APPLICATION_ATTACH_COMPLETED);
        } catch (RuntimeException | Error failure) {
            io.github.xjc.jiagu.local.LocalEventReporter.applicationCheckpoint(LocalStartupDiagnosticCode.APPLICATION_ATTACH_FAILED);
            throw failure;
        }
    }

}
