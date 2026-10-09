package io.github.xjc.jiagu.local;

import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;

public final class LocalEventInitializer extends ContentProvider {
    @Override public boolean onCreate() {
        try {
            Context c = getContext();
            android.os.Bundle meta = c.getPackageManager().getApplicationInfo(c.getPackageName(), PackageManager.GET_META_DATA).metaData;
            String name = meta == null ? "" : meta.getString("io.github.xjc.jiagu.local.UPLOADER", "");
            if (!name.isEmpty()) LocalEventReporter.initialize(c, name);
        } catch (Throwable failure) { android.util.Log.w("JiaguLocalEvents", "EVENT_PROVIDER_FAILED"); }
        return true;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
