package io.github.xjc.jiagu.local;

public enum LocalStartupStatus {
    STARTED("STARTED"),
    SUCCEEDED("SUCCEEDED"),
    FAILED("FAILED"),
    SKIPPED("SKIPPED");
    private final String wireName;
    LocalStartupStatus(String wireName) { this.wireName = wireName; }
    public String getWireName() { return wireName; }
    public static LocalStartupStatus fromWireName(String name) {
        for (LocalStartupStatus value : values()) if (value.wireName.equals(name)) return value;
        throw new IllegalArgumentException("Unknown LocalStartupStatus: " + name);
    }
}
