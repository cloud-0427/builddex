package io.github.xjc.jiagu.local;

/** Stable protocol IDs; declaration order is not execution order. */
public enum LocalStartupStage {
    STARTUP_ATTEMPT(1, "STARTUP_ATTEMPT"),
    PAYLOAD_DECRYPT(7, "PAYLOAD_DECRYPT"),
    LOCAL_PAYLOAD_READ(1001, "LOCAL_PAYLOAD_READ"),
    BUSINESS_DEX_PARSE(1002, "BUSINESS_DEX_PARSE"),
    BUSINESS_CLASSLOADER_CREATE(1003, "BUSINESS_CLASSLOADER_CREATE"),
    REAL_APPLICATION_CREATE(1004, "REAL_APPLICATION_CREATE"),
    EVENT_REPORTER_READY(1005, "EVENT_REPORTER_READY"),
    FIRST_ACTIVITY_RESUMED(1006, "FIRST_ACTIVITY_RESUMED");
    private final int id;
    private final String wireName;
    LocalStartupStage(int id, String wireName) { this.id = id; this.wireName = wireName; }
    public int getId() { return id; }
    public String getWireName() { return wireName; }
    public static LocalStartupStage fromWireName(String name) {
        for (LocalStartupStage stage : values()) if (stage.wireName.equals(name)) return stage;
        throw new IllegalArgumentException("Unknown local startup stage: " + name);
    }
    public static LocalStartupStage fromId(int id) {
        for (LocalStartupStage stage : values()) if (stage.id == id) return stage;
        throw new IllegalArgumentException("Unknown local startup stage id: " + id);
    }
}
