package io.github.xjc.dexreport;

import org.gradle.api.flow.BuildWorkResult;
import org.gradle.api.flow.FlowAction;
import org.gradle.api.flow.FlowParameters;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Publishes at build completion, after every requested APK/AAB target has succeeded. */
public abstract class JiaguPublishFlowAction implements FlowAction<JiaguPublishFlowAction.Parameters> {
    private static final Logger LOGGER = Logging.getLogger(JiaguPublishFlowAction.class);

    public interface Parameters extends FlowParameters {
        @Input
        Property<BuildWorkResult> getBuildWorkResult();
        @Input
        Property<Boolean> getPublish();
        @Input
        Property<String> getServerUrl();
        @Input
        Property<String> getCompanyId();
        @Input
        Property<String> getCompanyApiKey();
        @Input
        Property<String> getBuildInvocationId();
        @Input
        Property<String> getReleaseMetadataPath();
    }

    @Override
    public void execute(Parameters parameters) throws Exception {
        if (parameters.getBuildWorkResult().get().getFailure().isPresent()) return;
        java.io.File metadataFile = new java.io.File(parameters.getReleaseMetadataPath().get());
        if (!metadataFile.isFile()) return;

        String metadata = new String(Files.readAllBytes(
                metadataFile.toPath()), StandardCharsets.UTF_8);
        String invocationId = optionalField(metadata, "buildInvocationId");
        if (!parameters.getBuildInvocationId().get().equals(invocationId)) return;
        if (!parameters.getPublish().get()) return;

        String releaseId = optionalField(metadata, "releaseId");
        if (releaseId.isEmpty()) throw new IllegalStateException("Invalid Jiagu release metadata: missing releaseId");
        if (!shouldPublish(metadata, true)) {
            LOGGER.lifecycle("[Jiagu] Release 已发布且构建内容一致，跳过重复发布: " +
                            "companyId={}, packageName={}, versionCode={}, releaseId={}",
                    parameters.getCompanyId().get(), optionalField(metadata, "packageName"),
                    optionalNumberField(metadata, "versionCode"), releaseId);
            return;
        }
        new JiaguServerClient(parameters.getServerUrl().get(), parameters.getCompanyId().get(),
                parameters.getCompanyApiKey().get()).publish(releaseId);
        LOGGER.lifecycle("[Jiagu] Release 发布成功: companyId={}, packageName={}, versionCode={}, " +
                        "releaseId={}, status=PUBLISHED",
                parameters.getCompanyId().get(), optionalField(metadata, "packageName"),
                optionalNumberField(metadata, "versionCode"), releaseId);
    }

    static boolean shouldPublish(String metadata, boolean publishRequested) {
        return publishRequested && !"PUBLISHED".equals(optionalField(metadata, "status"));
    }

    private static String optionalField(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) +
                "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String optionalNumberField(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) +
                "\\\"\\s*:\\s*(-?\\d+)").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }
}
