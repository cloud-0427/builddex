package io.github.xjc.dexreport;

import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * 针对特定构建类型（如 debug, release）的加固配置覆盖。
 */
public abstract class DexReportBuildType {
    private final String name;

    /**
     * Creates a build-type-specific configuration object.
     *
     * @param name Gradle build type name
     */
    @Inject
    public DexReportBuildType(String name) {
        this.name = name;
    }

    /**
     * Returns this Gradle build type's name.
     *
     * @return build type name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns whether the release is published after a successful build.
     *
     * @return publish switch property
     */
    public abstract Property<Boolean> getPublish();

    /**
     * Returns whether anti-debug protection is enabled.
     *
     * @return anti-debug switch property
     */
    public abstract Property<Boolean> getAntiDebugEnabled();
}
