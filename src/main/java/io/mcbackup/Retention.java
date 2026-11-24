package io.mcbackup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which older snapshots to delete after a new one has been committed.
 *  - a new DAILY replaces every hourly older than it
 *  - a new WEEKLY replaces every daily (and stray hourly) older than it
 *  - safety caps keep a failing daily/weekly job from letting snapshots pile up forever
 * The newest snapshot of each tier is never deleted.
 */
public final class Retention {
    private Retention() { }

    public static List<SnapshotRef> plan(Tier newTier, SnapshotRef created, List<SnapshotRef> existing, BackupConfig cfg) {
        Set<SnapshotRef> del = new LinkedHashSet<>();
        for (SnapshotRef s : existing) {
            if (s.equals(created)) continue;
            boolean older = s.ts().isBefore(created.ts());
            if (newTier == Tier.DAILY && s.tier() == Tier.HOURLY && older) del.add(s);
            if (newTier == Tier.WEEKLY && (s.tier() == Tier.HOURLY || s.tier() == Tier.DAILY) && older) del.add(s);
        }
        cap(existing, del, Tier.HOURLY, cfg.maxHourly);
        cap(existing, del, Tier.DAILY, cfg.maxDaily);
        cap(existing, del, Tier.WEEKLY, cfg.keepWeekly);
        del.remove(created);
        return new ArrayList<>(del);
    }

    private static void cap(List<SnapshotRef> existing, Set<SnapshotRef> del, Tier tier, int keep) {
        List<SnapshotRef> remaining = new ArrayList<>();
        for (SnapshotRef s : existing) if (s.tier() == tier && !del.contains(s)) remaining.add(s);
        remaining.sort(Comparator.comparing(SnapshotRef::ts).reversed());
        for (int i = Math.max(1, keep); i < remaining.size(); i++) del.add(remaining.get(i));
    }
}
