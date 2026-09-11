package io.github.xjc.dexreport;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertEquals;

public class R8ArtifactDetectorTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void recognizesR8OnlyWhenEveryClassHasStrongMetadata() throws Exception {
        File jar = jar(new Entry("a/A.class", r8()), new Entry("b/B.class", r8()));
        R8ArtifactDetector.Result result = R8ArtifactDetector.inspectJar(jar.toPath());
        assertEquals(R8ArtifactDetector.Classification.R8_PROCESSED, result.classification);
        assertEquals(2, result.markedClassCount);
    }

    @Test public void leavesOrdinaryClassesRaw() throws Exception {
        File jar = jar(new Entry("com/example/A.class", bytes("ordinary class data")));
        assertEquals(R8ArtifactDetector.Classification.RAW,
                R8ArtifactDetector.inspectJar(jar.toPath()).classification);
    }

    @Test public void rejectsMixedArtifactsInsteadOfGuessing() throws Exception {
        File jar = jar(new Entry("a/A.class", r8()),
                new Entry("com/example/B.class", bytes("ordinary class data")));
        assertEquals(R8ArtifactDetector.Classification.CONFLICTING_EVIDENCE,
                R8ArtifactDetector.inspectJar(jar.toPath()).classification);
    }

    @Test public void ignoresJavaMultiReleaseMetadataClasses() throws Exception {
        File jar = jar(new Entry("a/A.class", r8()),
                new Entry("META-INF/versions/9/module-info.class", bytes("module metadata")));
        assertEquals(R8ArtifactDetector.Classification.R8_PROCESSED,
                R8ArtifactDetector.inspectJar(jar.toPath()).classification);
    }

    private File jar(Entry... entries) throws Exception {
        File file = temporary.newFile("input-" + System.nanoTime() + ".jar");
        try (JarOutputStream output = new JarOutputStream(new FileOutputStream(file))) {
            for (Entry entry : entries) {
                output.putNextEntry(new JarEntry(entry.name));
                output.write(entry.bytes);
                output.closeEntry();
            }
        }
        return file;
    }

    private static byte[] r8() {
        return bytes("class constant pool ~~R8{metadata} r8-map-id-0123456789");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static final class Entry {
        final String name;
        final byte[] bytes;

        Entry(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }
}
