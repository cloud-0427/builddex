package io.github.xjc.dexreport;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.Assert.*;

public class ServiceDescriptorsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void mergesProvidersAndKeepsOnlyServiceContractAndConstructors() throws Exception {
        ServiceDescriptors services = new ServiceDescriptors();
        services.add("META-INF/services/example.Spi", "example.B # comment\nexample.A\n".getBytes(StandardCharsets.UTF_8));
        services.add("META-INF/services/example.Spi", "example.B\nexample.C\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(new TreeSet<>(Arrays.asList("example.A", "example.B", "example.C")), services.entries.get("example.Spi"));
        String rules = String.join("\n", services.keepRules());
        assertTrue(rules.contains("example.Spi { public <methods>; }"));
        assertTrue(rules.contains("example.C { public <init>(); }"));
        assertFalse(rules.contains("{ *; }"));
        Path output = temporary.newFile("services.jar").toPath();
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output))) { services.write(jar); }
        assertEquals(services.entries, ServiceDescriptors.read(output).entries);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output))) { new ServiceDescriptors().write(jar); }
        assertTrue(ServiceDescriptors.read(output).entries.isEmpty());
    }

    @Test(expected = java.io.IOException.class) public void rejectsInvalidProviderRuleInjection() throws Exception {
        new ServiceDescriptors().add("META-INF/services/example.Spi", "example.*".getBytes(StandardCharsets.UTF_8));
    }
}
