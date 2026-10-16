package io.github.xjc.jiagu.local;

/** Non-stage event names. Wire stage ID remains 0. */
public enum LocalStartupInternalEvent {
    SESSION_HEADER("SESSION_HEADER"),
    STARTUP_DIAGNOSTIC("STARTUP_DIAGNOSTIC");
    private final String wireName;
    LocalStartupInternalEvent(String wireName) { this.wireName = wireName; }
    public String getWireName() { return wireName; }
    public static LocalStartupInternalEvent fromWireName(String name) {
        for (LocalStartupInternalEvent value : values()) if (value.wireName.equals(name)) return value;
        throw new IllegalArgumentException("Unknown LocalStartupInternalEvent: " + name);
    }
}

