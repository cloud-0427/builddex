package io.github.xjc.dexreport;

import com.android.build.api.artifact.SingleArtifact;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/**
 * DEX 报告插件，适配 Java 1.8。
 */
public final class DexReportPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        // 1. 注册扩展配置 'dexReport'
        final DexReportExtension extension = project.getExtensions().create("dexReport", DexReportExtension.class);
        // 设置默认挂载的任务前缀为 "assemble"
        extension.getAttachToTask().convention("assemble");

        project.getLogger().lifecycle("[DexReport] 插件已加载: {}", project.getPath());

        project.getPluginManager().withPlugin("com.android.application", ignored -> {
            ApplicationAndroidComponentsExtension androidComponents =
                    project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);

            androidComponents.onVariants(androidComponents.selector().all(), variant -> {
                final String variantName = variant.getName();
                final String buildType = variant.getBuildType();
                final String taskName = "report" + capitalize(variantName) + "Dex";

                // 2. 注册 DEX 报告任务
                TaskProvider<DexReportTask> reportTaskProvider = project.getTasks().register(taskName, DexReportTask.class, task -> {
                    task.setGroup("dex report");
                    task.setDescription("打印 " + variantName + " 的 DEX 临时位置和数量");
                    task.getVariantName().set(variantName);
                    task.getApkDirectory().set(
                            variant.getArtifacts().get(SingleArtifact.APK.INSTANCE)
                    );
                    task.getAppBuildDirectory().set(project.getLayout().getBuildDirectory());
                });

                // 3. 在项目评估完成后，根据用户配置决定是否自动挂载依赖
                project.afterEvaluate(p -> {
                    boolean shouldAutoRun = false;
                    
                    if (extension.getAutoRunBuildTypes().isPresent()) {
                        if (extension.getAutoRunBuildTypes().get().contains(buildType)) {
                            shouldAutoRun = true;
                        }
                    }

                    if (shouldAutoRun) {
                        String prefix = extension.getAttachToTask().get();
                        String targetTaskName = prefix + capitalize(variantName);
                        
                        // 寻找目标任务并挂载依赖
                        p.getTasks().matching(t -> t.getName().equalsIgnoreCase(targetTaskName)).configureEach(t -> {
                            t.dependsOn(reportTaskProvider);
                        });
                    }
                });
            });
        });
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
