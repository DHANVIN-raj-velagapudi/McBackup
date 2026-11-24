package io.mcbackup;

/** Backup tiers: hourly -> daily -> weekly. */
public enum Tier {
    HOURLY("hourly"), DAILY("daily"), WEEKLY("weekly");

    public final String id;

    Tier(String id) {
        this.id = id;
    }

    public static Tier parse(String s) {
        for (Tier t : values()) {
            if (t.id.equalsIgnoreCase(s)) return t;
        }
        throw new IllegalArgumentException("Unknown tier '" + s + "' (use hourly, daily or weekly)");
    }
}
