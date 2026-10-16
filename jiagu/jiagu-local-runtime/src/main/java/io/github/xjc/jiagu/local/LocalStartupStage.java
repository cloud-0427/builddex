package io.github.xjc.jiagu.local;

/** Ordered for reading the main startup path. IDs are stable; never serialize ordinal(). */
public enum LocalStartupStage {
    STARTUP_ATTEMPT(1, "STARTUP_ATTEMPT", 10, "开始启动", false),
    LOCAL_PAYLOAD_READ(1001, "LOCAL_PAYLOAD_READ", 20, "Payload 读取及配置校验", false),
    PAYLOAD_DECRYPT(7, "PAYLOAD_DECRYPT", 30, "Payload 解密及校验", false),
    BUSINESS_DEX_PARSE(1002, "BUSINESS_DEX_PARSE", 40, "DEX 校验", false),
    BUSINESS_CLASSLOADER_CREATE(1003, "BUSINESS_CLASSLOADER_CREATE", 50, "ClassLoader 创建", false),
    REAL_APPLICATION_CREATE(1004, "REAL_APPLICATION_CREATE", 60, "Application 实例创建", false),
    // Worker-side branch: may occur before or after FIRST_ACTIVITY_RESUMED.
    EVENT_REPORTER_READY(1005, "EVENT_REPORTER_READY", 70, "上报器就绪（异步旁路）", true),
    FIRST_ACTIVITY_RESUMED(1006, "FIRST_ACTIVITY_RESUMED", 80, "首次 Activity 恢复", false);
    private final int id;
    private final String wireName;
    private final int displayOrder;
    private final String displayName;
    private final boolean asynchronous;
    LocalStartupStage(int id, String wireName, int displayOrder, String displayName, boolean asynchronous) {
        this.id = id; this.wireName = wireName; this.displayOrder = displayOrder;
        this.displayName = displayName; this.asynchronous = asynchronous;
    }
    public int getId() { return id; }
    public String getWireName() { return wireName; }
    public int getDisplayOrder() { return displayOrder; }
    public String getDisplayName() { return displayName; }
    public boolean isAsynchronous() { return asynchronous; }
    public static LocalStartupStage fromWireName(String name) {
        for (LocalStartupStage stage : values()) if (stage.wireName.equals(name)) return stage;
        throw new IllegalArgumentException("Unknown local startup stage: " + name);
    }
    public static LocalStartupStage fromId(int id) {
        for (LocalStartupStage stage : values()) if (stage.id == id) return stage;
        throw new IllegalArgumentException("Unknown local startup stage id: " + id);
    }
}
