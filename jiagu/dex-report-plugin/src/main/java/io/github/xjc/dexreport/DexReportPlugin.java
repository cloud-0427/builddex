package io.github.xjc.dexreport;

import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.artifact.SingleArtifact;
import com.android.build.api.dsl.ApkSigningConfig;
import com.android.build.api.dsl.ApplicationBuildType;
import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ScopedArtifacts;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.flow.FlowProviders;
import org.gradle.api.flow.FlowScope;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.UUID;

import javax.inject.Inject;

/**
 * Plugin entry point for bytecode protection, manifest transformation, JNI injection,
 * and Android resource obfuscation.
 */
public final class DexReportPlugin implements Plugin<Project> {
    private final FlowScope flowScope;
    private final FlowProviders flowProviders;

    @Inject
    public DexReportPlugin(FlowScope flowScope, FlowProviders flowProviders) {
        this.flowScope = flowScope;
        this.flowProviders = flowProviders;
    }

    @Override
    public void apply(Project project) {
        DexReportExtension extension = project.getExtensions().create("dexReport", DexReportExtension.class);

        // 设置默认值
        extension.getSignatureCheckEnabled().convention(true);
        extension.getResObfuscationEnabled().convention(true);
        extension.getCertificateSha256Digests().convention(java.util.Collections.emptySet());

        project.getPluginManager().withPlugin("com.android.application", plugin -> {
            addJiaguRuntimeDependency(project);

            ApplicationAndroidComponentsExtension androidComponents =
                    project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
            ApplicationExtension androidExtension =
                    project.getExtensions().getByType(ApplicationExtension.class);

            androidComponents.onVariants(androidComponents.selector().all(), variant -> {
                String variantName = variant.getName();
                String buildTypeName = variant.getBuildType();
                String variantCap = capitalize(variantName);
                String buildInvocationId = UUID.randomUUID().toString();

                TaskProvider<JiaguTask> jiaguTaskProvider = project.getTasks().register(
                        "jiagu" + variantCap, JiaguTask.class, task -> {
                            task.setGroup("jiagu");
                            DexReportExtension ext = project.getExtensions().getByType(DexReportExtension.class);
                            task.getServerUrl().set(ext.getServerUrl());
                            task.getCompanyId().set(ext.getCompanyId());
                            task.getCompanyApiKey().set(ext.getCompanyApiKey());
                            task.getPackageName().set(variant.getApplicationId());
                            if (!variant.getOutputs().isEmpty()) {
                                task.getVersionName().set(variant.getOutputs().get(0).getVersionName());
                                task.getVersionCode().set(project.provider(() -> {
                                    java.util.Set<Integer> versions = new java.util.LinkedHashSet<>();
                                    variant.getOutputs().forEach(output -> versions.add(output.getVersionCode().get()));
                                    if (versions.size() != 1) {
                                        throw new IllegalStateException("Jiagu requires every output of variant " +
                                                variantName + " to use one versionCode, but found " + versions);
                                    }
                                    return versions.iterator().next();
                                }));
                            }
                            task.getCertificateSha256().set(project.provider(() ->
                                    SigningCertificate.sha256Base64Url(resolveSigningConfig(
                                            androidExtension, variant.getBuildType()))));
                            task.getCertificateSha256Digests().set(ext.getCertificateSha256Digests().map(
                                    values -> new java.util.ArrayList<>(values)));
                            String producerTaskName = variant.getShrinkResources()
                                    ? "convertShrunkResourcesToBinary" + variantCap
                                    : "process" + variantCap + "Resources";
                            String resourcePackagePath = variant.getShrinkResources()
                                    ? "intermediates/shrunk_resources_binary_format/" + variantName + "/" +
                                            producerTaskName + "/shrunk-resources-binary-format-" + variantName + ".ap_"
                                    : "intermediates/linked_resources_binary_format/" + variantName + "/" +
                                            producerTaskName + "/linked-resources-binary-format-" + variantName + ".ap_";
                            task.getResourcePackage().set(project.getLayout().getBuildDirectory().file(resourcePackagePath));
                            task.getMergedAssets().set(variant.getArtifacts().get(SingleArtifact.ASSETS.INSTANCE));
                            task.getNativeInputs().from(project.fileTree(project.getProjectDir(), spec -> {
                                spec.include("src/**/jniLibs/**/*.so");
                            }));
                            task.getNativeInputs().from(project.fileTree(
                                    project.getLayout().getBuildDirectory(), spec -> {
                                        spec.include("intermediates/cxx/**/obj/**/*.so");
                                        spec.exclude("**/liblog_ext.so");
                                    }));
                            project.getConfigurations().matching(configuration ->
                                    configuration.getName().equals(variantName + "RuntimeClasspath"))
                                    .all(configuration -> task.getNativeInputs().from(
                                            configuration.getIncoming().artifactView(view -> {
                                                view.setLenient(true);
                                                view.getAttributes().attribute(
                                                        ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-jni");
                                            }).getFiles()));

                            // 合并配置：BuildType Specific -> Global Default -> Auto Detection
                            boolean isDebug = buildTypeName != null && buildTypeName.toLowerCase().contains("debug");
                            DexReportBuildType specific = buildTypeName != null ? ext.getBuildTypes().findByName(buildTypeName) : null;

                            if (specific != null && specific.getPublish().isPresent()) {
                                task.getPublish().set(specific.getPublish());
                            } else if (ext.getPublish().isPresent()) {
                                task.getPublish().set(ext.getPublish());
                            } else {
                                task.getPublish().set(!isDebug);
                            }

                            if (specific != null && specific.getAntiDebugEnabled().isPresent()) {
                                task.getAntiDebugEnabled().set(specific.getAntiDebugEnabled());
                            } else if (ext.getAntiDebugEnabled().isPresent()) {
                                task.getAntiDebugEnabled().set(ext.getAntiDebugEnabled());
                            } else {
                                task.getAntiDebugEnabled().set(!isDebug);
                            }

                            task.getNdkDirectory().set(androidComponents.getSdkComponents().getNdkDirectory());
                            task.getOutJniLibsDir().set(project.getLayout().getBuildDirectory()
                                    .dir("generated/jiagu/jniLibs/" + variantName));
                            task.getReleaseMetadataFile().set(project.getLayout().getBuildDirectory()
                                    .file("intermediates/jiagu/" + variantName + "/release.json"));
                            task.getPayloadFile().set(project.getLayout().getBuildDirectory()
                                    .file("intermediates/jiagu/" + variantName + "/payload.jg3"));
                            task.getBusinessDexSha256File().set(project.getLayout().getBuildDirectory()
                                    .file("intermediates/jiagu/" + variantName + "/business-dex.sha256"));
                            task.getBuildInvocationId().set(buildInvocationId);
                        });

                TaskProvider<JiaguReleaseTask> releaseTaskProvider = project.getTasks().register(
                        "createJiaguRelease" + variantCap, JiaguReleaseTask.class, task -> {
                            task.setGroup("jiagu");
                            task.getServerUrl().set(jiaguTaskProvider.flatMap(JiaguTask::getServerUrl));
                            task.getCompanyId().set(jiaguTaskProvider.flatMap(JiaguTask::getCompanyId));
                            task.getCompanyApiKey().set(jiaguTaskProvider.flatMap(JiaguTask::getCompanyApiKey));
                            task.getPackageName().set(jiaguTaskProvider.flatMap(JiaguTask::getPackageName));
                            task.getVersionCode().set(jiaguTaskProvider.flatMap(JiaguTask::getVersionCode));
                            task.getCertificateSha256().set(jiaguTaskProvider.flatMap(JiaguTask::getCertificateSha256));
                            task.getCertificateSha256Digests().set(
                                    jiaguTaskProvider.flatMap(JiaguTask::getCertificateSha256Digests));
                            task.getPublish().set(jiaguTaskProvider.flatMap(JiaguTask::getPublish));
                            task.getBuildInvocationId().set(buildInvocationId);
                            task.getPayloadFile().set(jiaguTaskProvider.flatMap(JiaguTask::getPayloadFile));
                            task.getBusinessDexSha256File().set(
                                    jiaguTaskProvider.flatMap(JiaguTask::getBusinessDexSha256File));
                            task.getResourcePackage().set(jiaguTaskProvider.flatMap(JiaguTask::getResourcePackage));
                            task.getMergedAssets().set(variant.getArtifacts().get(SingleArtifact.ASSETS.INSTANCE));
                            task.getNativeInputs().from(project.fileTree(project.getProjectDir(), spec ->
                                    spec.include("src/**/jniLibs/**/*.so")));
                            task.getNativeInputs().from(project.fileTree(
                                    project.getLayout().getBuildDirectory(), spec -> {
                                        spec.include("intermediates/cxx/**/obj/**/*.so");
                                        spec.exclude("**/liblog_ext.so");
                                    }));
                            project.getConfigurations().matching(configuration ->
                                    configuration.getName().equals(variantName + "RuntimeClasspath"))
                                    .all(configuration -> task.getNativeInputs().from(
                                            configuration.getIncoming().artifactView(view -> {
                                                view.setLenient(true);
                                                view.getAttributes().attribute(
                                                        ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-jni");
                                            }).getFiles()));
                            task.getNdkDirectory().set(androidComponents.getSdkComponents().getNdkDirectory());
                            task.getOutJniLibsDir().set(project.getLayout().getBuildDirectory()
                                    .dir("generated/jiagu/jniLibs/" + variantName));
                            task.getReleaseMetadataFile().set(project.getLayout().getBuildDirectory()
                                    .file("intermediates/jiagu/" + variantName + "/release.json"));
                        });

                project.getTasks().matching(task -> task.getName().equals("externalNativeBuild" + variantCap))
                        .all(nativeTask -> releaseTaskProvider.configure(task -> task.dependsOn(nativeTask)));
                String nativeBuildCap = capitalize(buildTypeName == null ? variantName : buildTypeName);
                for (Project candidate : project.getRootProject().getAllprojects()) {
                    candidate.getTasks().matching(task ->
                            task.getName().equals("externalNativeBuild" + nativeBuildCap)).all(
                            nativeTask -> releaseTaskProvider.configure(task -> task.dependsOn(nativeTask)));
                }

                variant.getArtifacts().forScope(ScopedArtifacts.Scope.ALL)
                        .use(jiaguTaskProvider)
                        .toTransform(
                                ScopedArtifact.CLASSES.INSTANCE,
                                JiaguTask::getAllJars,
                                JiaguTask::getAllDirectories,
                                JiaguTask::getOutputJar);

                variant.getSources().getJniLibs().addGeneratedSourceDirectory(
                        releaseTaskProvider, JiaguReleaseTask::getOutJniLibsDir);

                DexReportExtension ext = project.getExtensions().getByType(DexReportExtension.class);
                TaskProvider<ManifestTransformerTask> manifestTaskProvider = project.getTasks().register(
                        "modifyManifest" + variantCap, ManifestTransformerTask.class, task -> {
                            task.getAntiDebugEnabled().set(jiaguTaskProvider.flatMap(JiaguTask::getAntiDebugEnabled));
                            task.getSignatureCheckEnabled().set(ext.getSignatureCheckEnabled());
                            task.getExpectedSignature().set(ext.getExpectedSignature());
                        });

                variant.getArtifacts().use(manifestTaskProvider)
                        .wiredWithFiles(
                                ManifestTransformerTask::getMergedManifest,
                                ManifestTransformerTask::getUpdatedManifest)
                        .toTransform(SingleArtifact.MERGED_MANIFEST.INSTANCE);

                if (ext.getResObfuscationEnabled().get()) {
                    TaskProvider<ResObfuscatorTask> resources = registerResourceObfuscation(
                            project, variantName, variantCap, variant.getShrinkResources(), ext);
                    releaseTaskProvider.configure(task -> task.dependsOn(resources));
                } else {
                    String producer = variant.getShrinkResources()
                            ? "convertShrunkResourcesToBinary" + variantCap
                            : "process" + variantCap + "Resources";
                    project.getTasks().matching(task -> task.getName().equals(producer)).all(
                            resourceTask -> releaseTaskProvider.configure(task -> task.dependsOn(resourceTask)));
                }

                flowScope.always(JiaguPublishFlowAction.class, spec -> {
                    JiaguPublishFlowAction.Parameters parameters = spec.getParameters();
                    parameters.getBuildWorkResult().set(flowProviders.getBuildWorkResult());
                    parameters.getPublish().set(releaseTaskProvider.flatMap(JiaguReleaseTask::getPublish));
                    parameters.getServerUrl().set(ext.getServerUrl());
                    parameters.getCompanyId().set(ext.getCompanyId());
                    parameters.getCompanyApiKey().set(ext.getCompanyApiKey());
                    parameters.getBuildInvocationId().set(buildInvocationId);
                    parameters.getReleaseMetadataPath().set(project.getLayout().getBuildDirectory()
                            .file("intermediates/jiagu/" + variantName + "/release.json")
                            .map(file -> file.getAsFile().getAbsolutePath()));
                });
            });
        });
    }

    private static ApkSigningConfig resolveSigningConfig(
            ApplicationExtension androidExtension, String buildTypeName) {
        if (buildTypeName == null) {
            throw new IllegalStateException("Jiagu requires a signed Android application variant");
        }
        ApplicationBuildType buildType = androidExtension.getBuildTypes().getByName(buildTypeName);
        ApkSigningConfig signingConfig = buildType.getSigningConfig();
        if (signingConfig == null && "debug".equals(buildTypeName)) {
            signingConfig = androidExtension.getSigningConfigs().findByName("debug");
        }
        if (signingConfig == null) {
            throw new IllegalStateException(
                    "Jiagu cannot resolve signingConfig for build type " + buildTypeName);
        }
        return signingConfig;
    }

    private TaskProvider<ResObfuscatorTask> registerResourceObfuscation(
            Project project,
            String variantName,
            String variantCap,
            boolean shrinkResources,
            DexReportExtension extension) {
        String producerTaskName = shrinkResources
                ? "convertShrunkResourcesToBinary" + variantCap
                : "process" + variantCap + "Resources";
        String optimizeTaskName = "optimize" + variantCap + "Resources";
        String packageTaskName = "package" + variantCap;
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
        // optimize<Variant>Resources can also be present when shrinkResources is false and
        // consumes the linked resource package directly. Do not infer the consumer from the
        // shrinkResources flag: both possible downstream tasks must observe the in-place
        // mutation performed by ResObfuscatorTask.
        project.getTasks().matching(task ->
                task.getName().equals(optimizeTaskName)
                        || task.getName().equals(packageTaskName)
        ).all(consumer -> consumer.dependsOn(obfuscator));
        return obfuscator;
    }

    private void addJiaguRuntimeDependency(Project project) {
        try {
            Project runtimeProject = project.getRootProject().findProject(":jiagu-runtime");
            if (runtimeProject != null) {
                project.getDependencies().add("implementation", project.project(":jiagu-runtime"));
            } else {
                project.getDependencies().add("implementation", runtimeCoordinates());
            }
        } catch (Exception ignored) {
            // Dependency may already be supplied by the consumer.
        }
    }

    private String runtimeCoordinates() throws IOException {
        Properties publication = new Properties();
        try (InputStream input = DexReportPlugin.class.getResourceAsStream(
                "/jiagu-publication.properties")) {
            if (input == null) {
                throw new IOException("Missing jiagu-publication.properties");
            }
            publication.load(input);
        }
        return publication.getProperty("runtime.group") + ":"
                + publication.getProperty("runtime.artifact") + ":"
                + publication.getProperty("runtime.version");
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
