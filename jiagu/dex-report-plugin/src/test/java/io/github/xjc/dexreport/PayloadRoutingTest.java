package io.github.xjc.dexreport;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PayloadRoutingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void allowlistKeepsThirdPartyInShellAndOwnCodeInPayload() {
        Set<String> includes = Collections.singleton("com.example.app.**");
        PayloadRouting.Decision own = PayloadRouting.route("com/example/app/Login.class", "allowlist",
                includes, Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        PayloadRouting.Decision thirdParty = PayloadRouting.route("androidx/work/Worker.class", "allowlist",
                includes, Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        assertEquals(PayloadRouting.Destination.PAYLOAD, own.destination);
        assertEquals(PayloadRouting.Destination.SHELL, thirdParty.destination);
    }

    @Test public void manifestProviderWinsOverAllowlistAndReportsConflict() {
        Set<String> includes = Collections.singleton("com.example.app.**");
        Set<String> startup = Collections.singleton("com.example.app.AnalyticsProvider");
        PayloadRouting.Decision provider = PayloadRouting.route("com/example/app/AnalyticsProvider.class",
                "allowlist", includes, Collections.emptySet(), Collections.emptySet(), startup);
        assertEquals(PayloadRouting.Destination.SHELL, provider.destination);
        assertTrue(provider.startupAllowlistConflict);
    }

    @Test public void startupScannerFindsProviderFactoryAndInitializer() throws Exception {
        File manifest = temporary.newFile("AndroidManifest.xml");
        String xml = "<manifest package=\"com.example.app\" xmlns:android=\"http://schemas.android.com/apk/res/android\">"
                + "<application android:appComponentFactory=\".Factory\">"
                + "<provider android:name=\".BootProvider\"><meta-data android:name=\"x\" "
                + "android:value=\"com.vendor.Initializer\"/></provider></application></manifest>";
        Files.write(manifest.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        Set<String> classes = PayloadRouting.startupClasses(manifest, "unused");
        assertTrue(classes.contains("com.example.app.Factory"));
        assertTrue(classes.contains("com.example.app.BootProvider"));
        assertTrue(classes.contains("com.vendor.Initializer"));
    }

    @Test public void invalidAllowlistIsRejected() {
        try {
            PayloadRouting.validate("allowlist", new LinkedHashSet<>(), "validate", "fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("payloadIncludePackages"));
            return;
        }
        throw new AssertionError("expected invalid allowlist to fail");
    }

    @Test public void fixedRuntimeClassCannotEnterPayload() {
        PayloadRouting.Decision decision = PayloadRouting.route("io/github/xjc/jiagu/ProxyApplication.class",
                "allowlist", Collections.singleton("io.github.xjc.jiagu.**"), Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet());
        assertEquals(PayloadRouting.Destination.SHELL, decision.destination);
        assertFalse(decision.startupAllowlistConflict);
    }
}
