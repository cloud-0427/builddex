package io.github.xjc.dexreport;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
/** MVP entry point: local encryption only. Historical online tasks are not registered. */
public final class DexReportPlugin implements Plugin<Project> {
    @Override public void apply(Project project) { new LocalModePlugin().apply(project); }
}
