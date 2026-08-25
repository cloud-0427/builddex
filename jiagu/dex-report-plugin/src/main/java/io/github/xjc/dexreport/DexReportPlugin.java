package io.github.xjc.dexreport;

import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.artifact.SingleArtifact;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ScopedArtifacts;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/**
 * Plugin entry point for bytecode protection, manifest transformation, JNI injection,
 * and Android resource obfuscation.
 */
public final class DexReportPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        DexReportExtension extension = project.getExtensions().create("dexReport", DexReportExtension.class);

        extension.getEnableMultiVersion().convention(true);
        extension.getPublicKeyJsonKey().convention("akmKeys");
        extension.getKeyExpiryDays().convention(2);
        extension.getAntiDebugEnabled().convention(true);
        extension.getSignatureCheckEnabled().convention(true);
        extension.getResObfuscationEnabled().convention(true);

        project.getPluginManager().withPlugin("com.android.application", plugin -> {
            addJiaguRuntimeDependency(project);

            ApplicationAndroidComponentsExtension androidComponents =
                    project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);

            androidComponents.onVariants(androidComponents.selector().all(), variant -> {
                String variantName = variant.getName();
                String variantCap = capitalize(variantName);

                TaskProvider<JiaguTask> jiaguTaskProvider = project.getTasks().register(
                        "jiagu" + variantCap, JiaguTask.class, task -> {
                            task.setGroup("jiagu");
                            DexReportExtension ext = project.getExtensions().getByType(DexReportExtension.class);
                            task.getPublicKeyPath().set(ext.getPublicKeyPath());
                            task.getPublicKeyJsonKey().set(ext.getPublicKeyJsonKey());
                            task.getEnableMultiVersion().set(ext.getEnableMultiVersion());
                            task.getPackageName().set(variant.getApplicationId());
                            if (!variant.getOutputs().isEmpty()) {
                                task.getVersionName().set(variant.getOutputs().get(0).getVersionName());
                                task.getVersionCode().set(variant.getOutputs().get(0).getVersionCode());
                            }
                            task.getKeyExpiryDays().set(ext.getKeyExpiryDays());
                            task.getKeysFile().set(project.getRootProject().getLayout()
                                    .getProjectDirectory().file("jiagu_keys.json"));
                            task.getNdkDirectory().set(androidComponents.getSdkComponents().getNdkDirectory());
                            task.getOutJniLibsDir().set(project.getLayout().getBuildDirectory()
                                    .dir("generated/jiagu/jniLibs/" + variantName));
                        });

                variant.getArtifacts().forScope(ScopedArtifacts.Scope.ALL)
                        .use(jiaguTaskProvider)
                        .toTransform(
                                ScopedArtifact.CLASSES.INSTANCE,
                                JiaguTask::getAllJars,
                                JiaguTask::getAllDirectories,
                                JiaguTask::getOutputJar);

                variant.getSources().getJniLibs().addGeneratedSourceDirectory(
                        jiaguTaskProvider, JiaguTask::getOutJniLibsDir);

                DexReportExtension ext = project.getExtensions().getByType(DexReportExtension.class);
                TaskProvider<ManifestTransformerTask> manifestTaskProvider = project.getTasks().register(
                        "modifyManifest" + variantCap, ManifestTransformerTask.class, task -> {
                            task.getKeyUrl().set(ext.getPublicKeyPath());
                            task.getJsonKey().set(ext.getPublicKeyJsonKey());
                            task.getExpiryDays().set(ext.getKeyExpiryDays());
                            task.getAntiDebugEnabled().set(ext.getAntiDebugEnabled());
                            task.getSignatureCheckEnabled().set(ext.getSignatureCheckEnabled());
                            task.getExpectedSignature().set(ext.getExpectedSignature());
                        });

                variant.getArtifacts().use(manifestTaskProvider)
                        .wiredWithFiles(
                                ManifestTransformerTask::getMergedManifest,
                                ManifestTransformerTask::getUpdatedManifest)
                        .toTransform(SingleArtifact.MERGED_MANIFEST.INSTANCE);

                if (ext.getResObfuscationEnabled().get()) {
                    registerResourceObfuscation(project, variantName, variantCap,
                            variant.getShrinkResources(), ext);
                }
            });
        });
    }

    private void registerResourceObfuscation(
            Project project,
            String variantName,
            String variantCap,
            boolean shrinkResources,
            DexReportExtension extension) {
        String producerTaskName = shrinkResources
                ? "convertShrunkResourcesToBinary" + variantCap
                : "process" + variantCap + "Resources";
        String consumerTaskName = shrinkResources
                ? "optimize" + variantCap + "Resources"
                : "package" + variantCap;
        String resourcePackagePath = shrinkResources
                ? "intermediates/shrunk_resources_binary_format/" + variantName
                        + "/" + producerTaskName
                        + "/shrunk-resources-binary-format-" + variantName + ".ap_"
                : "intermediates/linked_resources_binary_format/" + variantName
                        + "/" + producerTaskName
                        + "/linked-resources-binary-format-" + variantName + ".ap_";

        TaskProvider<ResObfuscatorTask> obfuscator = project.getTasks().register(
                "obfuscateRes" + variantCap, ResObfuscatorTask.class, task -> {
                    task.setGroup("jiagu");
                    task.getResConfigs().set(extension.getResConfigs());
                    task.getInputResourcePackage().set(
                            project.getLayout().getBuildDirectory().file(resourcePackagePath));
                    task.getOutputResourcePackage().set(
                            project.getLayout().getBuildDirectory().file(resourcePackagePath));
                });

        // Resource shrinking makes process<Variant>Resources emit proto resources. In that
        // pipeline the first binary package is produced by convertShrunkResourcesToBinary.
        project.getTasks().matching(task -> task.getName().equals(producerTaskName)).all(producer -> {
            obfuscator.configure(task -> task.dependsOn(producer));
            producer.finalizedBy(obfuscator);
        });
        project.getTasks().matching(task -> task.getName().equals(consumerTaskName)).all(consumer ->
                consumer.dependsOn(obfuscator));
    }

    private void addJiaguRuntimeDependency(Project project) {
        try {
            Project runtimeProject = project.getRootProject().findProject(":jiagu-runtime");
            if (runtimeProject != null) {
                project.getDependencies().add("implementation", project.project(":jiagu-runtime"));
            } else {
                project.getDependencies().add("implementation", "io.github.xjc:jiagu-runtime:0.1.0");
            }
        } catch (Exception ignored) {
            // Dependency may already be supplied by the consumer.
        }
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
