package io.mcbackup;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Identifies one snapshot: tier + UTC timestamp, e.g. hourly/20261124T053500Z. */
public record SnapshotRef(Tier tier, Instant ts) implements Comparable<SnapshotRef> {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    public SnapshotRef {
        ts = ts.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    public String id() {
        return tier.id + "/" + FMT.format(ts.atOffset(ZoneOffset.UTC));
    }

    public static SnapshotRef parseId(String id) {
        int slash = id.indexOf('/');
        if (slash <= 0 || slash == id.length() - 1) {
            throw new IllegalArgumentException("Snapshot id must look like hourly/20261124T053500Z");
        }
        try {
            Tier t = Tier.parse(id.substring(0, slash));
            Instant ts = LocalDateTime.parse(id.substring(slash + 1), FMT).toInstant(ZoneOffset.UTC);
            return new SnapshotRef(t, ts);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Bad snapshot timestamp in '" + id + "'");
        }
    }

    @Override
    public int compareTo(SnapshotRef o) {
        int c = ts.compareTo(o.ts);
        return c != 0 ? c : tier.compareTo(o.tier);
    }

    @Override
    public String toString() {
        return id();
    }
}
