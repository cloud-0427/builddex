package lows.dgeon.ightr.jiagu;

import android.content.Context;
import android.util.Log;

public class Application extends android.app.Application {

    private static final String Tag = "Application";

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(Tag, "Myapp, onCreate !!!");
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        Log.i(Tag, "Myapp, attachBaseContext !!!");
    }

}
