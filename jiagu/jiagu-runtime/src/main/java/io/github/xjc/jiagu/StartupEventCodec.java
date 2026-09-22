package io.github.xjc.jiagu;

import java.io.*;

/** Versioned snapshot: replay never substitutes current session or package metadata. */
final class StartupEventCodec {
    static byte[] encode(JiaguStartupEvent e) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(2);
        out.writeInt(e.getStageId());
        out.writeUTF(e.getStatus().name());
        string(out, e.getResultCode()); string(out, e.getFailureClass()); string(out, e.getSessionId());
        string(out, e.getStartupInstanceId()); string(out, e.getPackageName());
        string(out, e.getVersionName()); out.writeLong(e.getVersionCode());
        out.writeLong(e.getOccurredAtMillis()); out.writeLong(e.getElapsedSinceStartMs());
        out.writeLong(e.getStageDurationMs()); out.writeUTF(e.getAuthorizationSource().name());
        string(out, e.getProcessName()); out.writeBoolean(e.isMainProcess());
        out.writeBoolean(e.isFirstLaunch()); string(out, e.getActivityName());
        out.writeLong(e.getActivityResumedElapsedMs()); out.flush();
        return bytes.toByteArray();
    }

    static JiaguStartupEvent decode(byte[] bytes) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int version = in.readInt();
            if (version != 1 && version != 2) throw new IOException("Unsupported startup event version");
            JiaguStartupEvent e = new JiaguStartupEvent(
                    JiaguStartupEvent.Stage.fromId(in.readInt()),
                    JiaguStartupEvent.Status.valueOf(in.readUTF()),
                    string(in), version == 2 ? string(in) : null, string(in), string(in), string(in), string(in),
                    in.readLong(), in.readLong(), in.readLong(), in.readLong(),
                    JiaguStartupEvent.AuthorizationSource.valueOf(in.readUTF()),
                    string(in), in.readBoolean(), in.readBoolean(), string(in), in.readLong());
            if (in.available() != 0) throw new IOException("Trailing startup event data");
            return e;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid startup event", e);
        }
    }

    private static void string(DataOutputStream out, String s) throws IOException {
        out.writeBoolean(s != null);
        if (s != null) out.writeUTF(s);
    }
    private static String string(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }
}
