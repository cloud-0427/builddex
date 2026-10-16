package io.github.xjc.jiagu.local;

/** Record categories; independent of business execution order. */
public enum LocalStartupRecordType {
    STAGE("STAGE"),
    CHECKPOINT("CHECKPOINT"),
    DIAGNOSTIC("DIAGNOSTIC");
    private final String wireName;
    LocalStartupRecordType(String wireName) { this.wireName = wireName; }
    public String getWireName() { return wireName; }
    public static LocalStartupRecordType fromWireName(String name) {
        for (LocalStartupRecordType value : values()) if (value.wireName.equals(name)) return value;
        throw new IllegalArgumentException("Unknown LocalStartupRecordType: " + name);
    }
}

