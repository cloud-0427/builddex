package io.github.xjc.jiagu.local;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Context-free private append log. A returned append has completed fd.sync(). */
final class LocalEventJournal implements Closeable {
    private static final java.util.Set<String> ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final int MAGIC = 0x4a475331, MAX_FRAME = 8192;
    final File directory, file;
    private final RandomAccessFile owner;
    private final FileLock alive;
    LocalEventJournal(File directory, String session) throws IOException {
        this.directory = directory;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("JOURNAL_DIRECTORY_FAILED");
        owner = new RandomAccessFile(new File(directory, session + ".alive"), "rw");
        alive = owner.getChannel().tryLock();
        if (alive == null) { owner.close(); throw new IOException("SESSION_LOCK_FAILED"); }
        file = new File(directory, session + ".journal");
        ACTIVE.add(file.getCanonicalPath());
    }
    synchronized void append(String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME) throw new IOException("JOURNAL_FRAME_TOO_LARGE");
        // Leave room for failure diagnostics even when an abusive custom emitter fills the log.
        if (file.length() + bytes.length + 16 > 512 * 1024) throw new IOException("JOURNAL_SESSION_LIMIT");
        long total = 0;
        File[] logs = directory.listFiles((d, n) -> n.endsWith(".journal") || n.endsWith(".corrupt"));
        if (logs == null || logs.length > 1024) throw new IOException("JOURNAL_DIRECTORY_LIMIT");
        for (File log : logs) total += log.length();
        if (total + bytes.length + 16 > 2 * 1024 * 1024) throw new IOException("JOURNAL_TOTAL_LIMIT");
        CRC32 crc = new CRC32(); crc.update(bytes);
        try (FileOutputStream stream = new FileOutputStream(file, true)) {
            DataOutputStream output = new DataOutputStream(stream);
            output.writeInt(MAGIC); output.writeInt(1); output.writeInt(bytes.length);
            output.write(bytes); output.writeInt((int) crc.getValue()); output.flush(); stream.getFD().sync();
        }
    }
    interface Consumer { void accept(long end, String payload) throws Exception; }
    /** Returns last complete offset; a truncated tail is retained for diagnosis. */
    static long read(File file, long offset, Consumer consumer) throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (offset < 0 || offset > input.length()) throw new CorruptException("JOURNAL_OFFSET_INVALID");
            input.seek(offset);
            while (input.getFilePointer() < input.length()) {
                long start = input.getFilePointer();
                if (input.length() - start < 16) return start;
                int magic = input.readInt(), format = input.readInt(), length = input.readInt();
                if (magic != MAGIC || format != 1 || length < 0 || length > MAX_FRAME) throw new CorruptException("JOURNAL_FRAME_INVALID");
                if (input.length() - input.getFilePointer() < length + 4L) return start;
                byte[] bytes = new byte[length]; input.readFully(bytes);
                CRC32 crc = new CRC32(); crc.update(bytes);
                if (input.readInt() != (int) crc.getValue()) throw new CorruptException("JOURNAL_CRC_INVALID");
                consumer.accept(input.getFilePointer(), new String(bytes, StandardCharsets.UTF_8));
            }
            return input.getFilePointer();
        }
    }
    static boolean dead(File directory, String session) throws IOException {
        // Do not open/close another fd for a locally held POSIX lock: closing it can release
        // process-owned advisory locks on some Android implementations.
        if (ACTIVE.contains(new File(directory, session + ".journal").getCanonicalPath())) return false;
        try (RandomAccessFile lock = new RandomAccessFile(new File(directory, session + ".alive"), "rw")) {
            try (FileLock acquired = lock.getChannel().tryLock()) { return acquired != null; }
            catch (OverlappingFileLockException active) { return false; }
        }
    }
    public void close() throws IOException {
        try { alive.release(); owner.close(); }
        finally { ACTIVE.remove(file.getCanonicalPath()); }
    }
    static final class CorruptException extends IOException { CorruptException(String code) { super(code); } }
}
