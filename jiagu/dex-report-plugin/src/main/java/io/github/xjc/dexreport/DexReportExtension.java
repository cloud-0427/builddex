package io.github.xjc.dexreport;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;

/**
 * DexReport 插件的配置扩展类。
 * 用于在 build.gradle 中通过 dexReport { ... } 块进行自定义配置。
 */
public abstract class DexReportExtension {

    /** @return protection mode property (`online` or `local`) */
    public abstract Property<String> getProtectionMode();

    /** @return whether JG payload entries are compressed */
    public abstract Property<Boolean> getPayloadCompressionEnabled();

    /** Enables the isolated Jiagu Runtime R8 pass in local mode. Defaults to true. */
    public abstract Property<Boolean> getShellMinificationEnabled();

    /**
     * @return build types that automatically run the Jiagu task
     */
    public abstract SetProperty<String> getAutoRunBuildTypes();

    /**
     * @return task prefix to which Jiagu is attached
     */
    public abstract Property<String> getAttachToTask();

    /** @return key-service URL property */
    public abstract Property<String> getServerUrl();

    /** @return server-side company identifier property */
    public abstract Property<String> getCompanyId();

    /** @return server API key property */
    public abstract Property<String> getCompanyApiKey();

    /** @return whether signature verification is enabled */
    public abstract Property<Boolean> getSignatureCheckEnabled();

    /** @return expected signing certificate digest */
    public abstract Property<String> getExpectedSignature();

    /** @return fully-qualified shell-side startup log uploader implementation */
    public abstract Property<String> getStartupLogUploaderClass();

    /** @return Payload selection mode */
    public abstract Property<String> getPayloadSelectionMode();

    /** @return package patterns eligible for the encrypted Payload */
    public abstract SetProperty<String> getPayloadIncludePackages();

    /** @return package patterns that must remain in the APK shell */
    public abstract SetProperty<String> getShellKeepPackages();

    /** @return fully-qualified classes that must remain in the APK shell */
    public abstract SetProperty<String> getShellKeepClasses();

    /** @return startup component conflict policy */
    public abstract Property<String> getStartupComponentPolicy();

    /** @return Payload R8 compatibility policy */
    public abstract Property<String> getPayloadR8Policy();

    /** @return additional allowed signing certificate SHA-256 Base64URL digests */
    public abstract SetProperty<String> getCertificateSha256Digests();

    /** @return whether resource obfuscation is enabled */
    public abstract Property<Boolean> getResObfuscationEnabled();

    /** @return resource configuration filters */
    public abstract org.gradle.api.provider.ListProperty<String> getResConfigs();

    /**
     * @return global default publish switch
     */
    public abstract Property<Boolean> getPublish();

    /**
     * @return global default anti-debug switch
     */
    public abstract Property<Boolean> getAntiDebugEnabled();

    /**
     * @return build-type-specific configuration container
     */
    public abstract NamedDomainObjectContainer<DexReportBuildType> getBuildTypes();

    /**
     * Configures build-type-specific overrides.
     *
     * @param action configuration action
     */
    public void buildTypes(Action<? super NamedDomainObjectContainer<DexReportBuildType>> action) {
        action.execute(getBuildTypes());
    }
}
