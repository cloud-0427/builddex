package io.github.xjc.dexreport;

import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;

/**
 * DexReport 插件的配置扩展类。
 * 用于在 build.gradle 中通过 dexReport { ... } 块进行自定义配置。
 */
public abstract class DexReportExtension {

    /**
     * 指定哪些构建类型 (BuildType) 需要自动运行报告任务。
     * <p>
     * 设置后，当执行对应的构建任务（如 assemble）时，会自动触发本插件的报告任务。
     * 支持的常见类型包括：
     * <ul>
     *   <li>"debug": 调试构建，通常包含非混淆的中间 DEX。</li>
     *   <li>"release": 发布构建，通常包含经过 R8 优化后的最终 DEX。</li>
     * </ul>
     * 示例：autoRunBuildTypes = ["debug", "release"]
     */
    public abstract SetProperty<String> getAutoRunBuildTypes();

    /**
     * 指定报告任务需要挂载（依赖）到哪个任务前缀之后运行。
     * <p>
     * 插件会自动将此前缀与当前的变体名称（Variant Name）拼接。
     * 常见的支持前缀包括：
     * <ul>
     *   <li>"assemble": (默认值) 对应生成 APK 的任务，如 assembleDebug。</li>
     *   <li>"bundle": 对应生成 App Bundle (AAB) 的任务，如 bundleRelease。</li>
     *   <li>"install": 对应安装到设备的任务，如 installDebug。</li>
     *   <li>"package": 对应打包阶段的任务。</li>
     * </ul>
     * 示例：attachToTask = "bundle"
     */
    public abstract Property<String> getAttachToTask();

    /** 密钥服务地址，例如 https://jiagu.example.com:8761。 */
    public abstract Property<String> getServerUrl();

    /** 服务端公司标识。 */
    public abstract Property<String> getCompanyId();

    /** 公司打包 API Key。仅用于构建期 HTTP 请求，不得写入 APK。 */
    public abstract Property<String> getCompanyApiKey();

    /**
     * 是否启用反调试和 Hook 检测，默认 true。
     */
    public abstract Property<Boolean> getAntiDebugEnabled();

    /**
     * 是否启用签名校验，默认 true。
     */
    public abstract Property<Boolean> getSignatureCheckEnabled();

    /**
     * 预期的签名 SHA-256 哈希值（小写 hex）。
     */
    public abstract Property<String> getExpectedSignature();

    /**
     * 是否启用资源混淆（res 路径重命名），默认 true。
     */
    public abstract Property<Boolean> getResObfuscationEnabled();

    /**
     * 资源语言过滤，示例：["zh", "en"]。如果不设置则不裁剪。
     */
    public abstract org.gradle.api.provider.ListProperty<String> getResConfigs();

}
