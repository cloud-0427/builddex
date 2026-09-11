package io.github.xjc.dexreport;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VerifyServicesTaskTest {
    @Test public void reportsShellPayloadClassCollisions() {
        assertEquals(Collections.singleton("k.a"), VerifyServicesTask.duplicateClassNames(
                Arrays.asList("k.a", "payload.Only"),
                Arrays.asList("k.a", "shell.Only")));
    }

    @Test public void acceptsDisjointClassSpaces() {
        assertTrue(VerifyServicesTask.duplicateClassNames(
                Collections.singleton("payload.Only"),
                Collections.singleton("shell.Only")).isEmpty());
    }
}
