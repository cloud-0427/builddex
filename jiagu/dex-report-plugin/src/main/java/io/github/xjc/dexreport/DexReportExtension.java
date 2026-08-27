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

    /**
     * 指定哪些构建类型 (BuildType) 需要自动运行报告任务。
     */
    public abstract SetProperty<String> getAutoRunBuildTypes();

    /**
     * 指定报告任务需要挂载（依赖）到哪个任务前缀之后运行。
     */
    public abstract Property<String> getAttachToTask();

    /** 密钥服务地址。 */
    public abstract Property<String> getServerUrl();

    /** 服务端公司标识。 */
    public abstract Property<String> getCompanyId();

    /** 公司打包 API Key。 */
    public abstract Property<String> getCompanyApiKey();

    /** 是否启用签名校验。 */
    public abstract Property<Boolean> getSignatureCheckEnabled();

    /** 预期的签名 SHA-256 哈希值。 */
    public abstract Property<String> getExpectedSignature();

    /** Additional allowed signing certificate SHA-256 Base64URL digests. */
    public abstract SetProperty<String> getCertificateSha256Digests();

    /** 是否启用资源混淆。 */
    public abstract Property<Boolean> getResObfuscationEnabled();

    /** 资源语言过滤。 */
    public abstract org.gradle.api.provider.ListProperty<String> getResConfigs();

    /**
     * 全局默认：是否在构建成功后自动将版本标记为 PUBLISHED。
     */
    public abstract Property<Boolean> getPublish();

    /**
     * 全局默认：是否启用反调试和 Hook 检测。
     */
    public abstract Property<Boolean> getAntiDebugEnabled();

    /**
     * 构建类型特定的配置容器。
     */
    public abstract NamedDomainObjectContainer<DexReportBuildType> getBuildTypes();

    /**
     * DSL 配置块：buildTypes { ... }
     */
    public void buildTypes(Action<? super NamedDomainObjectContainer<DexReportBuildType>> action) {
        action.execute(getBuildTypes());
    }
}
