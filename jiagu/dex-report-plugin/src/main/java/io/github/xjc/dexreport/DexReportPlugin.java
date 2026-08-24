package io.github.xjc.dexreport;

import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.artifact.SingleArtifact;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ScopedArtifacts;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/**
 * 加固插件核心逻辑：实现 Manifest 自动修改与产物拦截。
 */
public final class DexReportPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getExtensions().create("dexReport", DexReportExtension.class);

        project.getPluginManager().withPlugin("com.android.application", ignored -> {
            // 自动为宿主注入壳模块依赖
            addJiaguRuntimeDependency(project);

            ApplicationAndroidComponentsExtension androidComponents =
                    project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);

            androidComponents.onVariants(androidComponents.selector().all(), variant -> {
                final String variantName = variant.getName();
                
                // 1. 注册加固加密任务 (拦截 Classes)
                String jiaguTaskName = "jiagu" + capitalize(variantName);
                TaskProvider<JiaguTask> jiaguTaskProvider = project.getTasks().register(jiaguTaskName, JiaguTask.class, task -> {
                    task.setGroup("jiagu");
                    task.getAesKey().set(project.getExtensions().getByType(DexReportExtension.class).getAesKey());
                    task.getOutAssetsDir().set(
                        project.getLayout().getBuildDirectory().dir("generated/jiagu/assets/" + variantName)
                    );
                });

                variant.getArtifacts().forScope(ScopedArtifacts.Scope.ALL)
                        .use(jiaguTaskProvider)
                        .toTransform(
                            ScopedArtifact.CLASSES.INSTANCE,
                            JiaguTask::getAllJars,
                            JiaguTask::getAllDirectories,
                            JiaguTask::getOutputJar
                        );

                // 2. 注入加固资产
                variant.getSources().getAssets().addGeneratedSourceDirectory(
                        jiaguTaskProvider,
                        JiaguTask::getOutAssetsDir
                );

                // 3. 核心： Manifest 自动修改
                String manifestTaskName = "modifyManifest" + capitalize(variantName);
                TaskProvider<ManifestTransformerTask> manifestTaskProvider = 
                    project.getTasks().register(manifestTaskName, ManifestTransformerTask.class, task -> {
                        task.getAesKey().set(project.getExtensions().getByType(DexReportExtension.class).getAesKey());
                    });

                // 拦截并修改合并后的 Manifest
                variant.getArtifacts().use(manifestTaskProvider)
                        .wiredWithFiles(
                            ManifestTransformerTask::getMergedManifest,
                            ManifestTransformerTask::getUpdatedManifest
                        )
                        .toTransform(SingleArtifact.MERGED_MANIFEST.INSTANCE);
            });
        });
    }

    private void addJiaguRuntimeDependency(Project project) {
        try {
            Project runtimeProject = project.getRootProject().findProject(":jiagu-runtime");
            if (runtimeProject != null) {
                // 使用更标准的项目依赖添加方式
                project.getDependencies().add("implementation", project.project(":jiagu-runtime"));
            } else {
                project.getDependencies().add("implementation", "io.github.xjc:jiagu-runtime:0.1.0");
            }
        } catch (Exception ignored) {}
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
