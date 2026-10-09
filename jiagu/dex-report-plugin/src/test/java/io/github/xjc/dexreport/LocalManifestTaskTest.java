package io.github.xjc.dexreport;

import org.junit.Test;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class LocalManifestTaskTest {
    private Element application(String attributes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream((
                "<application xmlns:android=\"http://schemas.android.com/apk/res/android\" " + attributes + "/>")
                .getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }
    @Test public void customBackupAgentIsRejectedWithResolvedClassName() throws Exception {
        for (String name : new String[]{".Backup", "Backup", "com.example.Backup"}) {
            IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                    LocalManifestTask.validateSpecialEntrypoints(application("android:backupAgent=\"" + name + "\""), "com.example"));
            assertTrue(error.getMessage().contains("LOCAL_SPECIAL_ENTRY_UNSUPPORTED"));
            assertTrue(error.getMessage().contains("com.example.Backup"));
        }
    }
    @Test public void heavyWeightApplicationIsRejected() throws Exception {
        assertThrows(IllegalStateException.class, () -> LocalManifestTask.validateSpecialEntrypoints(
                application("android:cantSaveState=\"true\""), "com.example"));
    }
    @Test public void ordinaryBackupAndDirectBootDeclarationsArePreserved() throws Exception {
        LocalManifestTask.validateSpecialEntrypoints(application(
                "android:allowBackup=\"true\" android:directBootAware=\"true\" android:cantSaveState=\"false\""), "com.example");
    }
}
