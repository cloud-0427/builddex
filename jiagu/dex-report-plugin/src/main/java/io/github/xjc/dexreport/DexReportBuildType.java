package io.github.xjc.dexreport;

import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * 针对特定构建类型（如 debug, release）的加固配置覆盖。
 */
public abstract class DexReportBuildType {
    private final String name;

    @Inject
    public DexReportBuildType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /** 是否在构建成功后自动发布。 */
    public abstract Property<Boolean> getPublish();

    /** 是否启用反调试。 */
    public abstract Property<Boolean> getAntiDebugEnabled();
}
