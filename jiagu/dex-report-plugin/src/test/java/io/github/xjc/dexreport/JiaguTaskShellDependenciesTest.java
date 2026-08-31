package io.github.xjc.dexreport;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JiaguTaskShellDependenciesTest {
    @Test
    public void keepsAuthorizationHttpStackInShell() {
        assertTrue(JiaguTask.shouldKeepInShell("okhttp3/OkHttpClient.class"));
        assertTrue(JiaguTask.shouldKeepInShell("okio/Buffer.class"));
        assertTrue(JiaguTask.shouldKeepInShell("org/conscrypt/Conscrypt.class"));
        assertTrue(JiaguTask.shouldKeepInShell("kotlin/jvm/internal/Intrinsics.class"));
        assertTrue(JiaguTask.shouldKeepInShell("androidx/startup/Initializer.class"));
        assertFalse(JiaguTask.shouldKeepInShell("com/example/business/MainActivity.class"));
    }
}
