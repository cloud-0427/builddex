package io.github.xjc.jiagu.local;

/** Internal phase checkpoints in execution order; no new stage IDs. */
public enum LocalStartupCheckpoint {
    ENTER("ENTER"),
    NATIVE_PATHS("NATIVE_PATHS"),
    CONSTRUCT("CONSTRUCT");
    private final String wireName;
    LocalStartupCheckpoint(String wireName) { this.wireName = wireName; }
    public String getWireName() { return wireName; }
    public static LocalStartupCheckpoint fromWireName(String name) {
        for (LocalStartupCheckpoint value : values()) if (value.wireName.equals(name)) return value;
        throw new IllegalArgumentException("Unknown LocalStartupCheckpoint: " + name);
    }
}

