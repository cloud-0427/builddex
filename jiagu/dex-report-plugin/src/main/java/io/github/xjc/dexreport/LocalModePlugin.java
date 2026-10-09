package io.github.xjc.dexreport;

import com.android.build.api.artifact.*;
import com.android.build.api.variant.*;
import org.gradle.api.*;
import org.gradle.api.tasks.TaskProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class LocalModePlugin implements Plugin<Project> {
    public void apply(Project project) {
        DexReportExtension ext = project.getExtensions().create("dexReport", DexReportExtension.class);
        ext.getProtectionMode().convention("local");
        ext.getPayloadSelectionMode().convention("allowlist");
        ext.getPayloadIncludePackages().convention(Collections.emptySet());
        ext.getShellKeepPackages().convention(Collections.emptySet());
        ext.getShellKeepClasses().convention(Collections.emptySet());
        ext.getAutoRunBuildTypes().convention(Collections.emptySet());
        ext.getStartupComponentPolicy().convention("validate");
        ext.getPayloadR8Policy().convention("fail");
        ext.getShellMinificationEnabled().convention(true);
        ext.getStartupTelemetryEnabled().convention(false);
        ext.getStartupLogUploaderClass().convention("");
        ext.getResObfuscationEnabled().convention(false);
        ext.getPayloadCompressionEnabled().convention(false);
        ext.getPublish().convention(false);
        ext.getAntiDebugEnabled().convention(false);
        ext.getSignatureCheckEnabled().convention(false);
        project.getPluginManager().withPlugin("com.android.application", ignored -> {
            ApplicationAndroidComponentsExtension components = project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
            // AAR contains no online code; add it only to protected variants below.
            components.beforeVariants(components.selector().all(), builder -> {
                if (!"local".equals(ext.getProtectionMode().get())) throw new GradleException("MVP supports protectionMode=local only");
                if (selected(ext, builder.getBuildType())) builder.setMinifyEnabled(false);
            });
            components.onVariants(components.selector().all(), variant -> {
                if (!selected(ext, variant.getBuildType())) return;
                if (variant.getMinSdk().getApiLevel() < 29) throw new GradleException("Local MVP requires minSdk >= 29");
                if (!"allowlist".equals(ext.getPayloadSelectionMode().get()) || ext.getPayloadIncludePackages().get().isEmpty())
                    throw new GradleException("Local MVP requires a nonempty Payload allowlist");
                if (variant.getShrinkResources() || ext.getResObfuscationEnabled().get())
                    throw new GradleException("Local MVP requires shrinkResources/resObfuscationEnabled=false");
                com.android.build.api.dsl.ApplicationExtension android = project.getExtensions().getByType(com.android.build.api.dsl.ApplicationExtension.class);
                if (!android.getDynamicFeatures().isEmpty()) throw new GradleException("Local MVP does not support dynamic features");
                boolean telemetry = ext.getStartupTelemetryEnabled().get();
                String uploader = telemetry ? ext.getStartupLogUploaderClass().get().trim() : "";
                if (telemetry && !uploader.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+"))
                    throw new GradleException("Enabled telemetry requires startupLogUploaderClass");
                addRuntime(project, variant.getName());
                String name = variant.getName(), cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                TaskProvider<LocalManifestTask> manifest = project.getTasks().register("modifyManifest" + cap, LocalManifestTask.class, t -> {
                    t.getPackageName().set(variant.getApplicationId()); t.getTelemetryEnabled().set(telemetry); t.getUploaderClass().set(uploader);
                });
                variant.getArtifacts().use(manifest).wiredWithFiles(LocalManifestTask::getInputManifest, LocalManifestTask::getOutputManifest)
                        .toTransform(SingleArtifact.MERGED_MANIFEST.INSTANCE);
                TaskProvider<JiaguTask> split = project.getTasks().register("jiagu" + cap, JiaguTask.class, t -> {
                    t.setGroup("jiagu");
                    t.getMinifyEnabled().set(false); t.getDebuggable().set(variant.getDebuggable());
                    t.getMinApiLevel().set(variant.getMinSdk().getApiLevel()); t.getAntiDebugEnabled().set(false);
                    t.getPayloadCompressionEnabled().set(false); t.getPayloadSelectionMode().set(ext.getPayloadSelectionMode());
                    t.getPayloadIncludePackages().set(ext.getPayloadIncludePackages()); t.getShellKeepPackages().set(ext.getShellKeepPackages());
                    t.getShellKeepClasses().set(ext.getShellKeepClasses()); t.getStartupComponentPolicy().set(ext.getStartupComponentPolicy());
                    t.getPayloadR8Policy().set(ext.getPayloadR8Policy()); t.getStartupLogUploaderClass().set(uploader);
                    t.getRuntimeR8Enabled().set(ext.getShellMinificationEnabled()); t.getRuntimeR8Rules().set(runtimeRules());
                    t.getBootClasspath().from(components.getSdkComponents().getBootClasspath());
                    t.getMergedManifest().set(variant.getArtifacts().get(SingleArtifact.MERGED_MANIFEST.INSTANCE));
                    t.getPayloadFile().set(project.getLayout().getBuildDirectory().file("intermediates/jiagu/" + name + "/payload.jg4"));
                    t.getBusinessDexSha256File().set(project.getLayout().getBuildDirectory().file("intermediates/jiagu/" + name + "/business-dex.sha256"));
                    t.getShellKeepRulesFile().set(project.getLayout().getBuildDirectory().file("intermediates/jiagu/" + name + "/shell-keep-rules.pro"));
                    t.getRuntimeMappingFile().set(project.getLayout().getBuildDirectory().file("intermediates/jiagu/" + name + "/runtime-mapping.txt"));
                    t.getServiceDescriptorsFile().set(project.getLayout().getBuildDirectory().file("intermediates/jiagu/" + name + "/service-descriptors.jar"));
                    t.getInputIndexFile().set(project.getLayout().getBuildDirectory().file("intermediates/jiagu/" + name + "/input-index.json"));
                });
                variant.getArtifacts().forScope(ScopedArtifacts.Scope.ALL).use(split).toTransform(ScopedArtifact.CLASSES.INSTANCE,
                        JiaguTask::getAllJars, JiaguTask::getAllDirectories, JiaguTask::getOutputJar);
                TaskProvider<CreateLocalPayloadTask> payload = project.getTasks().register("createLocalPayload" + cap, CreateLocalPayloadTask.class, t -> {
                    t.getPayload().set(split.flatMap(JiaguTask::getPayloadFile)); t.getPackageName().set(variant.getApplicationId());
                    t.getManifest().set(manifest.flatMap(LocalManifestTask::getOutputManifest));
                    t.getExistingAssets().from(variant.getSources().getAssets().getStatic().map(layers -> {
                        java.util.List<java.io.File> files = new java.util.ArrayList<>();
                        layers.forEach(dirs -> dirs.forEach(dir -> files.add(new java.io.File(dir.getAsFile(), "jiagu/local-payload.jgl"))));
                        return files;
                    }));
                    t.getVersionCode().set(project.provider(() -> {
                        Set<Integer> versions = new HashSet<>(); variant.getOutputs().forEach(o -> versions.add(o.getVersionCode().get()));
                        if (versions.size() != 1) throw new GradleException("Local MVP requires one versionCode per variant");
                        return versions.iterator().next();
                    }));
                    t.getAssets().set(project.getLayout().getBuildDirectory().dir("generated/jiagu/assets/" + name));
                });
                variant.getSources().getAssets().addGeneratedSourceDirectory(payload, CreateLocalPayloadTask::getAssets);
                TaskProvider<VerifyServicesTask> verify = project.getTasks().register("verifyJiaguServices" + cap, VerifyServicesTask.class, t -> {
                    t.getApkDirectory().set(variant.getArtifacts().get(SingleArtifact.APK.INSTANCE));
                    t.getDescriptors().set(split.flatMap(JiaguTask::getServiceDescriptorsFile));
                    t.getPayload().set(split.flatMap(JiaguTask::getPayloadFile));
                    t.getEncryptedAsset().set(payload.flatMap(CreateLocalPayloadTask::getAssets).map(d -> d.file("jiagu/local-payload.jgl")));
                });
                project.getTasks().matching(t -> t.getName().equals("assemble" + cap)).configureEach(t -> t.dependsOn(verify));
                project.getTasks().matching(t -> t.getName().equals("bundle" + cap)).configureEach(t -> t.doFirst(task -> {
                    throw new GradleException("AAB split loading is not validated in local MVP; use assemble" + cap);
                }));
            });
        });
    }
    private static boolean selected(DexReportExtension e, String type) {
        return e.getAutoRunBuildTypes().get().isEmpty() || e.getAutoRunBuildTypes().get().contains(type);
    }
    private static void addRuntime(Project p, String variant) {
        Project local = p.getRootProject().findProject(":jiagu-local-runtime");
        Object dependency;
        if (local != null) dependency = p.project(":jiagu-local-runtime");
        else {
            Properties props = new Properties();
            try (InputStream in = LocalModePlugin.class.getResourceAsStream("/jiagu-publication.properties")) {
                if (in == null) throw new IOException("Missing publication properties"); props.load(in);
            } catch (IOException error) { throw new GradleException("Cannot resolve local runtime", error); }
            dependency = props.getProperty("runtime.group") + ":jiagu-local-runtime:" + props.getProperty("runtime.version");
        }
        p.getDependencies().add(variant + "Implementation", dependency);
    }
    private static String runtimeRules() {
        try (InputStream in = LocalModePlugin.class.getResourceAsStream("/META-INF/jiagu/runtime-consumer-rules.pro")) {
            if (in == null) throw new IOException("Missing Runtime rules");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new GradleException("Cannot load Runtime rules", error); }
    }
}
