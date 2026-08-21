package lows.dgeon.ightr.jiagu;

import android.util.Log;

public class Application extends android.app.Application {

    private static final String Tag = "Application";

    @Override
    public void onCreate() {
        super.onCreate();

        Log.d(Tag, "Myapp, onCreate !!!");
    }

}
