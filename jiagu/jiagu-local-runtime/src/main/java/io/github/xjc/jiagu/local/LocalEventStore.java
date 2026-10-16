package io.github.xjc.jiagu.local;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.nio.channels.*;
import java.util.*;

/** Platform-only outbox. History is bounded but retained for gap attribution after ACK. */
final class LocalEventStore implements Closeable {
    private static final Object IMPORT_LOCK = new Object();
    final SQLiteDatabase db;
    private final File directory;
    private final String owner = UUID.randomUUID().toString();
    LocalEventStore(File directory) throws IOException {
        this.directory = directory;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("STORE_DIRECTORY_FAILED");
        db = SQLiteDatabase.openOrCreateDatabase(new File(directory, "events.db"), null);
        if (db.getVersion() > 1) { db.close(); throw new IOException("STORE_VERSION_UNSUPPORTED"); }
        db.execSQL("PRAGMA synchronous=FULL");
        db.execSQL("CREATE TABLE IF NOT EXISTS records(id TEXT PRIMARY KEY, session TEXT NOT NULL, seq INTEGER, payload TEXT NOT NULL, kind TEXT, state TEXT, attempts INTEGER DEFAULT 0, next INTEGER DEFAULT 0, lease TEXT, leaseUntil INTEGER DEFAULT 0, occurred INTEGER, error TEXT DEFAULT '')");
        db.execSQL("CREATE INDEX IF NOT EXISTS delivery ON records(state,next)");
        db.execSQL("CREATE TABLE IF NOT EXISTS imports(name TEXT PRIMARY KEY, offset INTEGER NOT NULL DEFAULT 0, recovered INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS audit(code TEXT, detail TEXT, at INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS settings(name TEXT PRIMARY KEY, value TEXT)");
        db.execSQL("PRAGMA user_version=1");
    }
    synchronized void insert(LocalStartupEvent event) throws Exception {
        String payload = event.toJson().toString();
        String state = event.getRecordType() == LocalStartupRecordType.CHECKPOINT ? "LOCAL" : "PENDING";
        db.execSQL("INSERT OR IGNORE INTO records(id,session,seq,payload,kind,state,occurred) VALUES(?,?,?,?,?,?,?)",
                new Object[]{event.eventId,event.sessionId,event.sequence,payload,event.recordType,state,event.occurredAtMillis});
        {
            try (Cursor c = db.rawQuery("SELECT payload FROM records WHERE id=?", new String[]{event.eventId})) {
                if (!c.moveToFirst() || !payload.equals(c.getString(0))) throw new IOException("EVENT_ID_CONTENT_CONFLICT");
            }
        }
    }
    synchronized List<String> importJournals(LocalStartupEvent discovery) throws Exception {
        synchronized (IMPORT_LOCK) { return importLocked(discovery); }
    }
    private List<String> importLocked(LocalStartupEvent discovery) throws Exception {
        List<String> incomplete = new ArrayList<>();
        try (RandomAccessFile claim = new RandomAccessFile(new File(directory, "import.lock"), "rw");
             FileLock lock = claim.getChannel().tryLock()) {
            if (lock == null) return incomplete;
            File[] files = directory.listFiles((d, name) -> name.endsWith(".journal"));
            if (files == null) return incomplete;
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) {
                String name = file.getName(), session = name.substring(0, name.length() - 8);
                long offset = 0;
                try (Cursor c = db.rawQuery("SELECT offset FROM imports WHERE name=?", new String[]{name})) {
                    if (c.moveToFirst()) offset = c.getLong(0);
                }
                boolean dead = LocalEventJournal.dead(directory, session);
                if (!dead && offset == file.length()) continue;
                boolean[] corrupt = {false};
                db.beginTransaction();
                try {
                    final String journalName = name;
                    long end;
                    try { end = LocalEventJournal.read(file, offset, (position, payload) -> {
                        insert(LocalStartupEvent.decode(payload));
                        db.execSQL("INSERT OR IGNORE INTO imports(name,offset) VALUES(?,0)", new Object[]{journalName});
                        db.execSQL("UPDATE imports SET offset=? WHERE name=?", new Object[]{position, journalName});
                    }); } catch (LocalEventJournal.CorruptException | org.json.JSONException damaged) {
                        audit(LocalStartupDiagnosticCode.JOURNAL_CORRUPTED, session); corrupt[0] = true; end = offset;
                    }
                    if (dead) {
                        if (end < file.length()) audit(LocalStartupDiagnosticCode.JOURNAL_TRUNCATED, session);
                        db.execSQL("INSERT OR IGNORE INTO imports(name,offset) VALUES(?,?)", new Object[]{name, end});
                        try (Cursor c = db.rawQuery("SELECT recovered FROM imports WHERE name=?", new String[]{name})) {
                            if (c.moveToFirst() && c.getInt(0) == 0) {
                                incomplete.add(session);
                                String last = lastCheckpoint(session);
                                if (!last.isEmpty() && discovery != null) {
                                    LocalStartupEvent previous = LocalStartupEvent.decode(last);
                                    boolean resumed;
                                    try (Cursor completed = db.rawQuery("SELECT 1 FROM records WHERE session=? AND kind=? AND payload LIKE ? LIMIT 1", new String[]{session, LocalStartupRecordType.STAGE.getWireName(), "%" + LocalStartupStage.FIRST_ACTIVITY_RESUMED.getWireName() + "%"})) {
                                        resumed = completed.moveToFirst();
                                    }
                                    if (!resumed) {
                                        LocalStartupEvent recovery = new LocalStartupEvent(discovery.sessionId, "recovery-" + session,
                                                LocalStartupInternalEvent.STARTUP_DIAGNOSTIC.getWireName(), LocalStartupStatus.SUCCEEDED.getWireName(), LocalStartupDiagnosticCode.PREVIOUS_STARTUP_INCOMPLETE.getWireName(),
                                                discovery.occurredAtMillis, discovery.elapsedSinceStartMs, 0,
                                                discovery.packageName, discovery.versionCode, discovery.processName);
                                        recovery.sequence = ++discovery.sequence; recovery.recordType = LocalStartupRecordType.DIAGNOSTIC.getWireName();
                                        recovery.buildId = discovery.buildId; recovery.relatedSessionId = session;
                                        recovery.versionName = discovery.versionName;
                                        recovery.checkpoint = previous.stage + ":" + previous.status + ":" + previous.resultCode + ":" + previous.checkpoint;
                                        insert(recovery);
                                    }
                                }
                            }
                        }
                        db.execSQL("UPDATE imports SET recovered=1 WHERE name=?", new Object[]{name});
                    }
                    db.setTransactionSuccessful();
                } catch (Exception error) {
                    // Roll back offset with inserted frames; preserve file for the next recovery.
                    throw error;
                } finally { db.endTransaction(); }
                if (dead) {
                    if (corrupt[0]) {
                        if (!file.renameTo(new File(directory, session + ".corrupt"))) audit(LocalStartupDiagnosticCode.JOURNAL_QUARANTINE_FAILED, session);
                    } else if (!file.delete()) audit(LocalStartupDiagnosticCode.JOURNAL_DELETE_FAILED, session);
                    File alive = new File(directory, session + ".alive");
                    if (alive.exists() && !alive.delete()) audit(LocalStartupDiagnosticCode.LOCK_DELETE_FAILED, session);
                }
            }
        } catch (OverlappingFileLockException busy) { /* Another importer owns recovery. */ }
        return incomplete;
    }
    synchronized void audit(LocalStartupDiagnosticCode code, String detail) {
        db.execSQL("INSERT INTO audit(code,detail,at) VALUES(?,?,?)", new Object[]{code.getWireName(), detail, System.currentTimeMillis()});
        db.execSQL("DELETE FROM audit WHERE rowid NOT IN (SELECT rowid FROM audit ORDER BY rowid DESC LIMIT 128)");
    }
    synchronized String lastCheckpoint(String session) {
        try (Cursor c = db.rawQuery("SELECT payload FROM records WHERE session=? ORDER BY seq DESC LIMIT 1", new String[]{session})) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }
    synchronized Entry claim(long now) {
        long paused = Long.parseLong(setting("pauseUntil", "0"));
        if (paused > now && paused <= now + 900000) return null;
        db.beginTransaction();
        try {
            // Bound future times after a clock rollback; lease duration is at most one minute.
            db.execSQL("UPDATE records SET state='PENDING' WHERE state='IN_FLIGHT' AND (leaseUntil<=? OR leaseUntil>?)", new Object[]{now, now + 60_000});
            long previousClock = Long.parseLong(setting("lastClock", "0"));
            if (now + 60000 < previousClock)
                db.execSQL("UPDATE records SET next=? WHERE state='PENDING' AND next>?", new Object[]{now + 900_000, now + 900_000});
            if (now - previousClock >= 60000 || now < previousClock)
                db.execSQL("INSERT OR REPLACE INTO settings VALUES('lastClock',?)", new Object[]{Long.toString(now)});
            Entry entry = null;
            try (Cursor c = db.rawQuery("SELECT id,payload,attempts FROM records WHERE state='PENDING' AND next<=? ORDER BY occurred,seq LIMIT 1", new String[]{Long.toString(now)})) {
                if (c.moveToFirst()) entry = new Entry(c.getString(0), c.getString(1), c.getInt(2));
            }
            if (entry != null) db.execSQL("UPDATE records SET state='IN_FLIGHT',lease=?,leaseUntil=? WHERE id=?", new Object[]{owner, now + 60_000, entry.id});
            db.setTransactionSuccessful(); return entry;
        } finally { db.endTransaction(); }
    }
    synchronized void ack(String id) { update(id, "ACKED", 0, ""); }
    synchronized void reject(String id, String reason) { update(id, "QUARANTINED", 0, reason); }
    synchronized void retry(Entry entry, long next, String reason) {
        db.beginTransaction();
        try {
        update(entry.id, "PENDING", next, reason);
        db.execSQL("UPDATE records SET attempts=attempts+1 WHERE id=?", new Object[]{entry.id});
        db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized void pause(Entry entry, long now, String code) {
        db.beginTransaction();
        try {
            retry(entry, now + 900000, code);
            db.execSQL("INSERT OR REPLACE INTO settings VALUES('pauseUntil',?)", new Object[]{Long.toString(now + 900000)});
            audit(LocalStartupDiagnosticCode.TARGET_PAUSED, code); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized void target(String identity) {
        if (!identity.equals(setting("target", ""))) {
            db.execSQL("INSERT OR REPLACE INTO settings VALUES('target',?)", new Object[]{identity});
            db.execSQL("INSERT OR REPLACE INTO settings VALUES('pauseUntil','0')");
            db.execSQL("UPDATE records SET next=0 WHERE state='PENDING'");
        }
    }
    private String setting(String name, String fallback) {
        try (Cursor c = db.rawQuery("SELECT value FROM settings WHERE name=?", new String[]{name})) {
            return c.moveToFirst() ? c.getString(0) : fallback;
        }
    }
    private void update(String id, String state, long next, String reason) {
        db.execSQL("UPDATE records SET state=?,next=?,error=?,lease=NULL,leaseUntil=0 WHERE id=? AND lease=?", new Object[]{state, next, reason, id, owner});
    }
    synchronized void maintain(long now) {
        db.beginTransaction();
        try {
        // Audit IDs before deleting, including unconfirmed events, so capacity loss is explicit.
        try (Cursor c = db.rawQuery("SELECT id FROM records WHERE occurred<?", new String[]{Long.toString(now - 7L * 86400000)})) {
            List<String> ids = new ArrayList<>(); while (c.moveToNext()) ids.add(c.getString(0));
            for (String id : ids) { audit(LocalStartupDiagnosticCode.RETENTION_EVICTED, id); db.delete("records", "id=?", new String[]{id}); }
        }
        long bytes, count;
        try (Cursor c = db.rawQuery("SELECT count(*),coalesce(sum(length(CAST(payload AS BLOB))),0) FROM records", null)) {
            c.moveToFirst(); count=c.getLong(0); bytes=c.getLong(1);
        }
        if (count > 1024 || bytes > 2 * 1024 * 1024) {
            try (Cursor c = db.rawQuery("SELECT id,length(CAST(payload AS BLOB)) FROM records ORDER BY CASE WHEN state='ACKED' THEN 0 WHEN kind='CHECKPOINT' THEN 3 WHEN payload LIKE '%FAILED%' THEN 2 ELSE 1 END,occurred", null)) {
                List<String> evicted = new ArrayList<>();
                while ((count > 1024 || bytes > 2 * 1024 * 1024) && c.moveToNext()) {
                    evicted.add(c.getString(0)); count--; bytes-=c.getLong(1);
                }
                for (String id : evicted) { audit(LocalStartupDiagnosticCode.CAPACITY_EVICTED, id); db.delete("records", "id=?", new String[]{id}); }
            }
        }
        db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        File[] damaged = directory.listFiles((d,n) -> n.endsWith(".corrupt"));
        if (damaged != null) for (File file : damaged) {
            if (file.lastModified() < now - 7L * 86400000) {
                audit(LocalStartupDiagnosticCode.CORRUPT_FILE_EXPIRED, file.getName());
                if (!file.delete()) audit(LocalStartupDiagnosticCode.CORRUPT_FILE_DELETE_FAILED, file.getName());
            }
        }
        try (Cursor c = db.rawQuery("SELECT name FROM imports WHERE recovered=1 AND rowid NOT IN (SELECT rowid FROM imports ORDER BY rowid DESC LIMIT 2048)", null)) {
            List<String> obsolete = new ArrayList<>();
            while (c.moveToNext()) if (!new File(directory,c.getString(0)).exists()) obsolete.add(c.getString(0));
            for (String name : obsolete) db.delete("imports","name=?",new String[]{name});
        }
        File[] locks = directory.listFiles((d,n) -> n.endsWith(".alive"));
        if (locks != null) for (File file : locks) {
            String session = file.getName().substring(0,file.getName().length()-6);
            if (!new File(directory,session+".journal").exists()) {
                try {
                    if (LocalEventJournal.dead(directory,session) && !file.delete()) audit(LocalStartupDiagnosticCode.LOCK_DELETE_FAILED,session);
                } catch (IOException failure) { audit(LocalStartupDiagnosticCode.LOCK_PROBE_FAILED,session); }
            }
        }
    }
    public void close() { db.close(); }
    static final class Entry {
        final String id, payload; final int attempts;
        Entry(String id, String payload, int attempts) { this.id=id; this.payload=payload; this.attempts=attempts; }
    }
}
