package io.github.xjc.dexreport;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JiaguTaskShellDependenciesTest {
    @Test
    public void keepsOnlyLocalBootstrapAsFixedShell() {
        assertTrue(JiaguTask.shouldKeepInShell("io/github/xjc/jiagu/local/LocalComponentFactory.class"));
        assertTrue(JiaguTask.shouldKeepInShell("com/example/R$layout.class"));
        assertFalse(JiaguTask.shouldKeepInShell("okhttp3/OkHttpClient.class"));
        assertFalse(JiaguTask.shouldKeepInShell("org/conscrypt/Conscrypt.class"));
        assertFalse(JiaguTask.shouldKeepInShell("com/example/business/MainActivity.class"));
    }

    @Test
    public void configuredStartupUploaderIsKeptInShell() {
        assertTrue(JiaguTask.shouldKeepInShell(
                "com/example/StartupUploader.class", "com.example.StartupUploader"));
        assertTrue(JiaguTask.shouldKeepInShell(
                "com/example/StartupUploader$1.class", "com.example.StartupUploader"));
        assertFalse(JiaguTask.shouldKeepInShell(
                "com/example/Other.class", "com.example.StartupUploader"));
    }
}
