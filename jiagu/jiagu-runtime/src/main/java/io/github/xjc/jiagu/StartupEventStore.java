package io.github.xjc.jiagu;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import java.io.File;
import java.io.IOException;

/** Main-process, single-dispatcher outbox. Rows remain present during network I/O. */
final class StartupEventStore implements AutoCloseable {
    private static final long TTL = 7L * 24 * 60 * 60 * 1000;
    private final SQLiteDatabase db;

    StartupEventStore(Context context) {
        this(new File(context.getNoBackupFilesDir(), "jiagu-startup-v1.db"));
    }
    StartupEventStore(File path) {
        db = SQLiteDatabase.openOrCreateDatabase(path, null);
        db.execSQL("PRAGMA synchronous=FULL");
        db.execSQL("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS outbox (id TEXT PRIMARY KEY, payload BLOB NOT NULL, "
                + "created INTEGER NOT NULL, priority INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, "
                + "due INTEGER NOT NULL DEFAULT 0, quarantined INTEGER NOT NULL DEFAULT 0)");
    }

    synchronized boolean completed() {
        try (Cursor c = db.rawQuery("SELECT value FROM state WHERE key='completed'", null)) {
            return c.moveToFirst();
        }
    }

    synchronized void append(JiaguStartupEvent e) throws IOException {
        byte[] bytes = StartupEventCodec.encode(e);
        if (bytes.length > 65536) throw new IOException("Startup event exceeds size limit");
        db.beginTransaction();
        try {
            prune(System.currentTimeMillis());
            ContentValues row = new ContentValues();
            row.put("id", e.getTelemetryEventId()); row.put("payload", bytes);
            row.put("created", System.currentTimeMillis());
            row.put("priority", e.getStatus() == JiaguStartupEvent.Status.FAILED
                    || e.getStatus() == JiaguStartupEvent.Status.BLOCKED
                    || e.getStageId() == 12 ? 1 : 0);
            // execSQL propagates disk/constraint failures instead of returning -1 and
            // accidentally committing the first-launch marker without its event.
            db.execSQL("INSERT OR IGNORE INTO outbox(id,payload,created,priority) VALUES(?,?,?,?)",
                    new Object[]{e.getTelemetryEventId(), bytes, row.getAsLong("created"), row.getAsInteger("priority")});
            if (e.getStageId() == 12 && e.getStatus() == JiaguStartupEvent.Status.SUCCEEDED) {
                db.execSQL("INSERT OR REPLACE INTO state(key,value) VALUES('completed','1')");
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private void prune(long now) {
        int expired = db.delete("outbox", "created < ?", new String[]{Long.toString(now - TTL)});
        int dropped = 0;
        while (true) {
            try (Cursor c = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(length(payload)),0) FROM outbox", null)) {
                c.moveToFirst();
                if (c.getLong(0) < 1024 && c.getLong(1) < 4 * 1024 * 1024 - 65536) break;
            }
            db.execSQL("DELETE FROM outbox WHERE id=(SELECT id FROM outbox ORDER BY priority,created,rowid LIMIT 1)");
            dropped++;
        }
        if (expired + dropped > 0) Log.w("Jiagu_Startup", "Outbox cleanup expired=" + expired + " capacity=" + dropped);
    }

    synchronized Entry next(long now) {
        db.delete("outbox", "created < ?", new String[]{Long.toString(now - TTL)});
        try (Cursor c = db.rawQuery("SELECT id,payload,attempts FROM outbox WHERE quarantined=0 AND due<=? "
                + "ORDER BY created,rowid LIMIT 1", new String[]{Long.toString(now)})) {
            if (!c.moveToFirst()) return null;
            return new Entry(c.getString(0), c.getBlob(1), c.getInt(2));
        }
    }
    synchronized long nextDelay(long now) {
        try (Cursor c = db.rawQuery("SELECT MIN(due) FROM outbox WHERE quarantined=0", null)) {
            return c.moveToFirst() && !c.isNull(0) ? Math.max(0, c.getLong(0) - now) : -1;
        }
    }
    synchronized void acknowledge(String id) { db.delete("outbox", "id=?", new String[]{id}); }
    synchronized void retry(Entry e, long due) {
        ContentValues values = new ContentValues();
        values.put("attempts", Math.min(e.attempts + 1, 30)); values.put("due", due);
        db.update("outbox", values, "id=?", new String[]{e.id});
    }
    synchronized void quarantine(String id) {
        ContentValues values = new ContentValues(); values.put("quarantined", 1);
        db.update("outbox", values, "id=?", new String[]{id});
    }
    @Override public synchronized void close() { db.close(); }

    static final class Entry {
        final String id; final byte[] payload; final int attempts;
        Entry(String id, byte[] payload, int attempts) { this.id=id; this.payload=payload; this.attempts=attempts; }
    }
}
