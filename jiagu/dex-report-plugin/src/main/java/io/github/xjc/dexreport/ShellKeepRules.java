package io.github.xjc.dexreport;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.tracereferences.TraceReferences;
import com.android.tools.r8.tracereferences.TraceReferencesCommand;
import com.android.tools.r8.tracereferences.TraceReferencesKeepRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/** Preserves the ABI used by the already compiled, independently optimized payload. */
final class ShellKeepRules {
    private ShellKeepRules() {}

    static void generate(Collection<Path> businessDex, Path shellJar,
                         Collection<Path> bootClasspath, Path output)
            throws IOException, CompilationFailedException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        // Trace the final DEX, not the original JAR: removed business code must not
        // keep otherwise unused shell dependencies alive. The target is only the shell.
        // This consumer emits target rules; it deliberately does not validate unrelated
        // optional platform APIs (e.g. sun.misc.Unsafe, absent from android.jar).
        TraceReferences.run(TraceReferencesCommand.builder()
                .addSourceFiles(businessDex)
                .addTargetFiles(shellJar)
                .addLibraryFiles(bootClasspath)
                .setConsumer(TraceReferencesKeepRules.builder()
                        .setAllowObfuscation(false)
                        .setOutputPath(output)
                        .build())
                .build());
        // A pinned package-private superclass may have unpinned subclasses that R8
        // repackages. Permit access widening while retaining the exact external ABI.
        String rules = Files.readString(output, StandardCharsets.UTF_8);
        Files.writeString(output, rules.replace("-keep ", "-keep,allowaccessmodification "),
                StandardCharsets.UTF_8);
    }
}
