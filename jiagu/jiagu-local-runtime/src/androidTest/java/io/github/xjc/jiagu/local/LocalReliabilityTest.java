package io.github.xjc.jiagu.local;

import android.database.Cursor;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Android filesystem, fd.sync and SQLite transactions. Never calls a remote endpoint. */
public final class LocalReliabilityTest {
    private File directory() {
        File root = InstrumentationRegistry.getInstrumentation().getTargetContext().getNoBackupFilesDir();
        return new File(root, "p0-test-" + UUID.randomUUID());
    }
    private LocalStartupEvent event(String id, long seq) {
        LocalStartupEvent e = new LocalStartupEvent("old-session", id, "PAYLOAD_DECRYPT", "SUCCEEDED", "PAYLOAD_VERIFIED",
                System.currentTimeMillis(), 20, 10, "example.app", 24, "example.app");
        e.sequence = seq; e.buildId = "original-build"; return e;
    }
    private long count(LocalEventStore store, String where) {
        try (Cursor c = store.db.rawQuery("SELECT count(*) FROM records " + where, null)) { c.moveToFirst(); return c.getLong(0); }
    }
    @Test public void journalSyncImportRetryAndDuplicateAreStable() throws Exception {
        File dir = directory(); LocalStartupEvent e = event("stable-id", 1);
        try (LocalEventJournal j = new LocalEventJournal(dir, "old-session"); LocalEventStore s = new LocalEventStore(dir)) {
            j.append(e.toJson().toString()); j.append(e.toJson().toString());
            s.importJournals(null); s.importJournals(null);
            assertEquals(1, count(s, ""));
            LocalEventStore.Entry first = s.claim(System.currentTimeMillis()); assertNotNull(first);
            assertEquals(e.toJson().toString(), first.payload);
            s.retry(first, System.currentTimeMillis() - 1, "NETWORK_FAILURE");
            LocalEventStore.Entry retry = s.claim(System.currentTimeMillis());
            assertEquals(first.id, retry.id); assertEquals(first.payload, retry.payload);
            s.ack(retry.id); assertNull(s.claim(System.currentTimeMillis()));
            assertEquals(1, count(s, "WHERE state='ACKED'"));
        }
    }
    @Test public void closedSessionRecoversOnceAndKeepsLastCheckpoint() throws Exception {
        File dir = directory(); LocalStartupEvent checkpoint = event("checkpoint-id", 1);
        checkpoint.recordType = "CHECKPOINT"; checkpoint.checkpoint = "ENTER";
        try (LocalEventJournal j = new LocalEventJournal(dir, "old-session")) { j.append(checkpoint.toJson().toString()); }
        try (LocalEventStore s = new LocalEventStore(dir)) {
            LocalStartupEvent discovery = event("discovery", 1);
            s.importJournals(discovery); s.importJournals(discovery);
            assertEquals(1, count(s, "WHERE kind='CHECKPOINT'"));
            assertEquals(1, count(s, "WHERE id='recovery-old-session'"));
            assertFalse(new File(dir, "old-session.journal").exists());
        }
    }
    @Test public void halfFramePreservesCommittedPrefix() throws Exception {
        File dir = directory(); File file;
        try (LocalEventJournal j = new LocalEventJournal(dir, "old-session")) {
            j.append(event("committed", 1).toJson().toString()); file = j.file;
        }
        try (FileOutputStream out = new FileOutputStream(file, true)) { out.write(new byte[]{0x4a, 0x47, 0x53}); out.getFD().sync(); }
        try (LocalEventStore s = new LocalEventStore(dir)) {
            s.importJournals(null); assertEquals(1, count(s, "WHERE id='committed'"));
            try (Cursor c = s.db.rawQuery("SELECT count(*) FROM audit WHERE code='JOURNAL_TRUNCATED'", null)) {
                c.moveToFirst(); assertEquals(1, c.getInt(0));
            }
        }
    }
    @Test public void contentConflictIsRejectedAndExpiredLeaseIsRecovered() throws Exception {
        try (LocalEventStore s = new LocalEventStore(directory())) {
            LocalStartupEvent e = event("same-id", 1); s.insert(e);
            LocalStartupEvent conflicting = event("same-id", 2);
            try { s.insert(conflicting); fail("conflict accepted"); } catch (IOException expected) { }
            assertNotNull(s.claim(System.currentTimeMillis()));
            s.db.execSQL("UPDATE records SET leaseUntil=0");
            assertEquals("same-id", s.claim(System.currentTimeMillis()).id);
        }
    }
    @Test public void failedImportRollsBackOffsetAndEventTogether() throws Exception {
        File dir = directory();
        try (LocalEventJournal j = new LocalEventJournal(dir, "old-session"); LocalEventStore s = new LocalEventStore(dir)) {
            j.append(event("frame-1", 1).toJson().toString()); j.append(event("frame-2", 2).toJson().toString());
            s.db.execSQL("CREATE TRIGGER fail_second BEFORE INSERT ON records WHEN NEW.id='frame-2' BEGIN SELECT RAISE(ABORT,'test'); END");
            try { s.importJournals(null); fail("transaction should fail"); } catch (Exception expected) { }
            assertEquals(0, count(s, ""));
            s.db.execSQL("DROP TRIGGER fail_second"); s.importJournals(null); assertEquals(2, count(s, ""));
        }
    }
    @Test public void exactReadHandlesShortReadsTruncationAndExcess() throws Exception {
        byte[] data = new byte[1025]; new Random(7).nextBytes(data);
        InputStream shortReads = new ByteArrayInputStream(data) {
            public synchronized int read(byte[] b, int o, int n) { return super.read(b, o, Math.min(n, 7)); }
        };
        assertArrayEquals(data, LocalLoaderRegistry.readExactly(shortReads, data.length));
        try { LocalLoaderRegistry.readExactly(new ByteArrayInputStream(data), data.length + 1); fail(); } catch (EOFException expected) { }
        try { LocalLoaderRegistry.readExactly(new ByteArrayInputStream(data), data.length - 1); fail(); } catch (IOException expected) { }
    }
    @Test public void pausePreservesEventsAndConfigChangeResumesTarget() throws Exception {
        try (LocalEventStore s = new LocalEventStore(directory())) {
            s.target("build-a"); s.insert(event("paused-id", 1));
            LocalEventStore.Entry first = s.claim(System.currentTimeMillis());
            s.pause(first, System.currentTimeMillis(), "HTTP_401");
            assertNull(s.claim(System.currentTimeMillis()));
            assertEquals(1, count(s, "WHERE state='PENDING'"));
            s.target("build-b");
            assertNotNull(s.claim(System.currentTimeMillis()));
        }
    }
    @Test public void corruptedFrameDoesNotBlockOtherJournals() throws Exception {
        File dir = directory(); File corrupt;
        try (LocalEventJournal j = new LocalEventJournal(dir, "a-session")) { j.append(event("good-prefix",1).toJson().toString()); corrupt=j.file; }
        try (FileOutputStream out = new FileOutputStream(corrupt,true)) { out.write(new byte[16]); out.getFD().sync(); }
        try (LocalEventJournal j = new LocalEventJournal(dir,"b-session")) { j.append(event("other-event",2).toJson().toString()); }
        try (LocalEventStore s = new LocalEventStore(dir)) {
            s.importJournals(null);
            assertEquals(2, count(s,"")); assertTrue(new File(dir,"a-session.corrupt").exists());
        }
    }
    @Test public void eventSnapshotSurvivesLocaleChangeAndDecode() throws Exception {
        LocalStartupEvent e=event("frozen",1); String original=e.toJson().toString();
        Locale previous=Locale.getDefault();
        try {
            Locale.setDefault(Locale.JAPAN);
            assertEquals(original,LocalStartupEvent.decode(original).toJson().toString());
        } finally { Locale.setDefault(previous); }
    }
    @Test public void retentionEvictionIsAuditedByEventId() throws Exception {
        try (LocalEventStore s = new LocalEventStore(directory())) {
            LocalStartupEvent expired=new LocalStartupEvent("old","expired", "PAYLOAD_DECRYPT","FAILED","TEST",
                    System.currentTimeMillis()-8L*86400000,0,0,"example",1,"example");
            s.insert(expired); s.maintain(System.currentTimeMillis());
            assertEquals(0,count(s,""));
            try (Cursor c=s.db.rawQuery("SELECT detail FROM audit WHERE code='RETENTION_EVICTED'",null)) {
                assertTrue(c.moveToFirst()); assertEquals("expired",c.getString(0));
            }
        }
    }
    @Test public void largePayloadReadBenchmarkReportsBothPaths() throws Exception {
        byte[] bytes = new byte[4*1024*1024]; new Random(3).nextBytes(bytes);
        long[] exact = new long[9], legacy = new long[9];
        for(int i=0;i<9;i++) {
            long at=System.nanoTime();
            assertArrayEquals(bytes,LocalLoaderRegistry.readExactly(new ByteArrayInputStream(bytes),bytes.length));
            exact[i]=System.nanoTime()-at;
            at=System.nanoTime();
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] chunk=new byte[8192]; int n;
            try(InputStream in=new ByteArrayInputStream(bytes)) { while((n=in.read(chunk))!=-1) out.write(chunk,0,n); }
            assertArrayEquals(bytes,out.toByteArray()); legacy[i]=System.nanoTime()-at;
        }
        Arrays.sort(exact); Arrays.sort(legacy);
        android.os.Bundle report=new android.os.Bundle();
        report.putString("stream","P0_READ_4M_MEDIAN_NS exact="+exact[4]+" legacy="+legacy[4]+"\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0,report);
    }
}
