package io.github.xjc.dexreport;

import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.*;

public class ShellKeepRulesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void tracesDexMembersInheritanceAndSignaturesWithoutKeepingWholeLibrary()
            throws Exception {
        Path shell = jar("shell.jar", BoundaryBase.class, BoundaryChild.class,
                BoundaryValue.class, BoundaryUnused.class);
        Path source = jar("source.jar", BoundarySource.class);
        Path library = jar("library.jar", Object.class);
        Path dex = temporary.newFolder("dex").toPath();
        D8.run(D8Command.builder().addProgramFiles(source).setMinApiLevel(29)
                .setOutput(dex, OutputMode.DexIndexed).build());
        java.util.Set<String> definitions = VerifyServicesTask.classNames(
                Files.readAllBytes(dex.resolve("classes.dex")));
        assertTrue(definitions.contains(BoundarySource.class.getName()));
        assertFalse("A reference is not proof that a class survived shrinking",
                definitions.contains(BoundaryChild.class.getName()));
        Path rules = temporary.getRoot().toPath().resolve("rules.pro");
        ShellKeepRules.generate(Collections.singletonList(dex.resolve("classes.dex")),
                shell, Collections.singletonList(library), rules);
        String result = Files.readString(rules, StandardCharsets.UTF_8);
        assertTrue(result, result.contains("BoundaryChild"));
        assertTrue(result, result.contains("BoundaryBase"));
        assertTrue(result, result.contains("BoundaryValue"));
        assertTrue(result, result.contains("inherited("));
        assertTrue(result, result.contains("value;"));
        assertFalse(result, result.contains("unused("));
        assertFalse(result, result.contains("BoundaryUnused"));
        assertFalse(result, result.contains("allowobfuscation"));
        assertTrue(result, result.contains("-keep,allowaccessmodification class"));
        assertFalse(result, result.contains("{ *; }"));

        // Re-generating after references disappear must replace stale keep rules.
        Path emptySource = jar("empty.jar", BoundaryEmptySource.class);
        ShellKeepRules.generate(Collections.singletonList(emptySource), shell,
                Collections.singletonList(library), rules);
        assertFalse(Files.readString(rules).contains("BoundaryChild"));
    }

    private Path jar(String name, Class<?>... classes) throws Exception {
        Path path = temporary.getRoot().toPath().resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (Class<?> type : classes) {
                String resource = type.getName().replace('.', '/') + ".class";
                output.putNextEntry(new JarEntry(resource));
                try (InputStream input = type.getResourceAsStream("/" + resource)) {
                    assertNotNull(input);
                    input.transferTo(output);
                }
                output.closeEntry();
            }
        }
        return path;
    }
}

class BoundaryBase {
    public BoundaryValue value;
    public BoundaryValue inherited(BoundaryValue value) { return value; }
    public void unused() {}
}
class BoundaryChild extends BoundaryBase {}
class BoundaryValue {}
class BoundaryUnused {}
class BoundarySource {
    public BoundaryValue call(BoundaryChild child) { return child.inherited(child.value); }
}
class BoundaryEmptySource {}
