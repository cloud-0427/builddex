package io.github.xjc.dexreport;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.R8;
import com.android.tools.r8.R8Command;
import com.android.tools.r8.OutputMode;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Collection;

/** Runs the isolated Jiagu Runtime R8 pass while leaving final dexing to AGP. */
final class RuntimeR8Processor {
    private RuntimeR8Processor() {}

    static void run(Path runtimeJar, Collection<Path> libraryFiles, Collection<Path> ruleFiles,
                    Path outputJar, Path mappingFile) throws Exception {
        Files.createDirectories(outputJar.toAbsolutePath().getParent());
        Files.createDirectories(mappingFile.toAbsolutePath().getParent());
        Files.deleteIfExists(outputJar);
        R8Command.Builder command = R8Command.builder()
                .addProgramFiles(runtimeJar)
                .addLibraryFiles(libraryFiles)
                .addProguardConfigurationFiles(new java.util.ArrayList<>(ruleFiles))
                .setOutput(outputJar, OutputMode.ClassFile)
                .setMode(CompilationMode.RELEASE)
                .setProguardMapOutputPath(mappingFile);
        R8.run(command.build());
    }
}
