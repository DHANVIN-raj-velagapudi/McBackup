package io.mcbackup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/** Runs backup jobs (snapshot + retention + cleanup), schedules them, verifies and restores. */
public final class BackupService {

    public record JobSummary(SnapshotEngine.Result snapshot, int deletedSnapshots, int deletedBlobs) {
        public String describe() {
            SnapshotEngine.Result r = snapshot;
            return r.ref().id() + ": " + r.files() + " files (" + mb(r.bytes()) + "), uploaded "
                    + r.uploadedBlobs() + " new/changed (" + mb(r.uploadedBytes()) + " compressed) in "
                    + (r.millis() / 1000) + "s; removed " + deletedSnapshots + " old snapshot(s), "
                    + deletedBlobs + " unused file(s)";
        }
    }

    public record RestoreResult(int files, long bytes, Path dir) {}

    /** Formats a size: MB below 1000 MB, GB from 1000 MB upwards. */
    static String mb(long bytes) {
        double m = bytes / 1_048_576.0;
        if (m >= 1000) return String.format(java.util.Locale.ROOT, "%.2f GB", m / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", m);
    }

    private final BackupConfig cfg;
    private final S3Client s3;
    private final Keys keys;
    private final Logger log;
    private final SnapshotEngine engine;
    private final SnapshotEngine.Hooks hooks;
    private final ReentrantLock jobLock = new ReentrantLock();
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private final Map<Tier, Long> lastFailMillis = new ConcurrentHashMap<>();
    private volatile Schedule.State state;
    private volatile String running;
    private volatile long lastGcFreed;
    private volatile String lastResult = "no backup has run yet";

    public BackupService(BackupConfig cfg, S3Client s3, Logger log, SnapshotEngine.Hooks hooks) {
        this.cfg = cfg;
        this.s3 = s3;
        this.log = log;
        this.keys = new Keys(cfg.keyRoot());
        this.engine = new SnapshotEngine(cfg, s3, keys, log);
        this.hooks = hooks;
        this.state = loadState();
    }

    /** A failure that is not fatal: goes to the plugin error log (with its stack trace); the command line tool just prints it. */
    private void reportFailure(String what, Exception e) {
        if (hooks == SnapshotEngine.Hooks.NOOP) log.warning("[McBackup] " + what + ": " + e.getMessage());
        else hooks.failed(what, e);
    }

    public String runningJob() {
        return running;
    }

    public String lastResult() {
        return lastResult;
    }

    public void cancelRunning() {
        cancel.set(true);
    }

    // ------------------------------------------------------------------ scheduled ticks

    /** Called periodically. Runs at most one due job. Returns true if a job ran (successfully or not). */
    public boolean tick(ZonedDateTime now) {
        if (jobLock.isLocked()) return false;
        Tier due = Schedule.due(now, state, cfg);
        if (due == null) return false;
        Long failed = lastFailMillis.get(due);
        if (failed != null && System.currentTimeMillis() - failed < cfg.retryMinutes * 60_000L) return false;
        try {
            JobSummary s = runJob(due, now);
            log.info("[McBackup] " + due.id + " backup OK - " + s.describe());
        } catch (Exception e) {
            lastFailMillis.put(due, System.currentTimeMillis());
            reportFailure(due.id + " backup FAILED (retry in " + cfg.retryMinutes + " min)", e);
        }
        return true;
    }

    // ------------------------------------------------------------------ one job

    public JobSummary runJob(Tier tier, ZonedDateTime scheduledAt) throws Exception {
        if (!jobLock.tryLock()) throw new IllegalStateException("A backup is already running (" + running + ")");
        try {
            running = tier.id;
            cancel.set(false);
            try {
                SnapshotEngine.Result r = engine.run(tier, hooks, cancel);

                // The new snapshot is committed. Only now is it safe to remove what it replaces.
                List<SnapshotRef> existing = listSnapshots();
                List<SnapshotRef> del = Retention.plan(tier, r.ref(), existing, cfg);
                if (!del.isEmpty()) {
                    // Never delete older backups unless the new one is provably complete in the bucket.
                    List<String> missing = verify(r.ref());
                    if (!missing.isEmpty()) {
                        throw new IOException("New snapshot " + r.ref().id() + " is missing " + missing.size()
                                + " data file(s) in the bucket; nothing was deleted");
                    }
                }
                int deleted = 0;
                lastGcFreed = 0;
                for (SnapshotRef d : del) {
                    s3.delete(keys.manifest(d));
                    deleted++;
                }
                int blobs = del.isEmpty() ? 0 : gcBlobs();
                addStat("taken_" + tier.id, 1);
                addStat("bytesUploaded", r.uploadedBytes());
                if (deleted > 0) addStat("snapshotsDeleted", deleted);

                state = Schedule.after(state, tier, scheduledAt);
                saveState();
                JobSummary summary = new JobSummary(r, deleted, blobs);
                lastResult = "OK " + summary.describe();
                writeLog(tier, r, deleted, blobs, lastGcFreed, null);
                lastFailMillis.remove(tier);
                return summary;
            } catch (Exception e) {
                lastResult = "FAILED " + tier.id + ": " + e.getMessage();
                writeLog(tier, null, 0, 0, 0, e.getMessage());
                throw e;
            }
        } finally {
            running = null;
            jobLock.unlock();
        }
    }

    // ------------------------------------------------------------------ listing / cleanup

    public List<SnapshotRef> listSnapshots() throws IOException {
        List<SnapshotRef> out = new ArrayList<>();
        for (String k : s3.list(keys.snapshotPrefix())) keys.parseManifestKey(k).ifPresent(out::add);
        Collections.sort(out);
        return out;
    }

    /**
     * Deletes blobs that no remaining snapshot refers to. If any manifest cannot be read, nothing is deleted.
     * Not safe against another McBackup instance writing to the same bucket prefix at the same time.
     */
    int gcBlobs() throws IOException {
        List<SnapshotRef> snaps = listSnapshots();
        if (snaps.isEmpty()) return 0; // never wipe everything
        Set<String> referenced = new HashSet<>();
        for (SnapshotRef s : snaps) {
            Manifest m = Manifest.parse(s3.getBytes(keys.manifest(s)));
            for (Manifest.Entry e : m.entries) referenced.add(e.sha());
        }
        int removed = 0;
        long freed = 0;
        lastGcFreed = 0;
        for (Map.Entry<String, Long> en : s3.listSizes(keys.blobPrefix()).entrySet()) {
            var sha = keys.parseBlobKey(en.getKey());
            if (sha.isPresent() && !referenced.contains(sha.get())) {
                s3.delete(en.getKey());
                removed++;
                freed += en.getValue();
            }
        }
        lastGcFreed = freed;
        if (removed > 0) addStat("blobsDeleted", removed);
        if (freed > 0) addStat("bytesFreed", freed);
        return removed;
    }

    // ------------------------------------------------------------------ verify / restore

    /** Returns the hashes of blobs the snapshot needs but the bucket does not have (empty = healthy). */
    public List<String> verify(SnapshotRef ref) throws IOException {
        Manifest m = Manifest.parse(s3.getBytes(keys.manifest(ref)));
        Set<String> have = new HashSet<>();
        for (String k : s3.list(keys.blobPrefix())) keys.parseBlobKey(k).ifPresent(have::add);
        Set<String> missing = new HashSet<>();
        for (Manifest.Entry e : m.entries) if (!have.contains(e.sha())) missing.add(e.sha());
        return new ArrayList<>(missing);
    }

    /** Downloads a snapshot into outDir (never touches the live server files). Every file's SHA-256 is checked. */
    public RestoreResult restore(SnapshotRef ref, Path outDir) throws IOException {
        Manifest m = Manifest.parse(s3.getBytes(keys.manifest(ref)));
        Path base = outDir.toAbsolutePath().normalize();
        Files.createDirectories(base);
        long bytes = 0;
        for (Manifest.Entry e : m.entries) {
            Path target = safeTarget(base, e.path());
            Files.createDirectories(target.getParent());
            Path part = target.resolveSibling(target.getFileName() + ".part");
            MessageDigest md = SnapshotEngine.sha256();
            long n = 0;
            try (InputStream in = new GZIPInputStream(s3.getStream(keys.blob(e.sha())), 64 * 1024);
                 OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) != -1) {
                    md.update(buf, 0, r);
                    out.write(buf, 0, r);
                    n += r;
                }
            } catch (IOException ex) {
                Files.deleteIfExists(part);
                throw ex;
            }
            if (!HexFormat.of().formatHex(md.digest()).equals(e.sha())) {
                Files.deleteIfExists(part);
                throw new IOException("Checksum mismatch while restoring " + e.path() + " (backup data is corrupt)");
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            Files.setLastModifiedTime(target, FileTime.fromMillis(e.mtime()));
            bytes += n;
        }
        return new RestoreResult(m.entries.size(), bytes, base);
    }

    static Path safeTarget(Path base, String rel) throws IOException {
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains("\\") || rel.indexOf('\0') >= 0) {
            throw new IOException("Refusing unsafe path in backup: " + rel);
        }
        for (String part : rel.split("/")) {
            if (part.equals("..") || part.equals(".") || part.isEmpty()) {
                throw new IOException("Refusing unsafe path in backup: " + rel);
            }
        }
        Path t = base.resolve(rel).normalize();
        if (!t.startsWith(base)) throw new IOException("Refusing unsafe path in backup: " + rel);
        return t;
    }

    /** Uploads, reads back and deletes a tiny object so credentials/bucket problems show up early. */
    public String selfTest() throws IOException {
        String key = keys.snapshotPrefix().replace("snapshots/", "") + "mcbackup-selftest.txt";
        byte[] data = ("mcbackup selftest " + System.currentTimeMillis()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        s3.putBytes(key, data);
        byte[] back = s3.getBytes(key);
        s3.delete(key);
        if (!java.util.Arrays.equals(data, back)) throw new IOException("Read-back did not match what was written");
        return "Bucket '" + cfg.bucket + "' OK: upload, download and delete all worked";
    }

    // ------------------------------------------------------------------ backup-log.md

    private Path logFile() {
        return cfg.dataDir.resolve("backup-log.md");
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** Appends one row to plugins/McBackup/backup-log.md. Never throws: logging must not break a backup. */
    private synchronized void writeLog(Tier tier, SnapshotEngine.Result r, int deleted, int blobs, long freed, String error) {
        try {
            String storage = "?";
            if (error == null) {
                try {
                    long total = 0;
                    for (long v : s3.listSizes(keys.blobPrefix()).values()) total += v;
                    for (long v : s3.listSizes(keys.snapshotPrefix()).values()) total += v;
                    storage = mb(total);
                } catch (Exception e) {
                    storage = "unknown";
                }
            }
            String when = java.time.ZonedDateTime.now(cfg.zone)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            StringBuilder row = new StringBuilder("| ").append(when).append(" | ").append(tier.id).append(" | ");
            if (error == null) {
                row.append("OK | ").append(r.uploadedBlobs()).append(" files, ").append(mb(r.uploadedBytes()))
                        .append(" | ").append(deleted).append(" backup(s) | ").append(blobs).append(" files, ").append(mb(freed))
                        .append(" | ").append(storage).append(" | ").append(r.ref().id()).append(" |");
            } else {
                row.append("FAILED | - | - | - | - | ").append(cell(error)).append(" |");
            }
            Files.createDirectories(cfg.dataDir);
            Path f = logFile();
            StringBuilder out = new StringBuilder();
            if (!Files.exists(f)) {
                out.append("# McBackup log\n\nTimes are in ").append(cfg.zone).append(". \"Freed\" is the data removed from the bucket by clean-up.\n\n")
                        .append("| Time | Type | Result | Uploaded | Backups deleted | Data freed | Storage used after | Snapshot / error |\n")
                        .append("|---|---|---|---|---|---|---|---|\n");
            }
            out.append(row).append('\n');
            Files.writeString(f, out.toString(), java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            reportFailure("could not write backup-log.md", e);
        }
    }

    // ------------------------------------------------------------------ statistics

    private Path statsFile() {
        return cfg.dataDir.resolve("state").resolve("stats.properties");
    }

    private synchronized Properties loadStats() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(statsFile())) {
            p.load(in);
        } catch (IOException ignored) {
            // no stats yet
        }
        return p;
    }

    private synchronized void addStat(String name, long delta) {
        Properties p = loadStats();
        if (p.getProperty("since") == null) p.setProperty("since", java.time.Instant.now().toString());
        long cur = 0;
        try { cur = Long.parseLong(p.getProperty(name, "0")); } catch (NumberFormatException ignored) { }
        p.setProperty(name, Long.toString(cur + delta));
        try {
            Files.createDirectories(statsFile().getParent());
            try (OutputStream out = Files.newOutputStream(statsFile())) {
                p.store(out, "McBackup statistics");
            }
        } catch (IOException e) {
            reportFailure("could not save statistics", e);
        }
    }

    private static long num(Properties p, String k) {
        try { return Long.parseLong(p.getProperty(k, "0")); } catch (NumberFormatException e) { return 0; }
    }

    /** Human-readable report: what is in the bucket right now, plus running totals since stats started. */
    public String stats() throws IOException {
        Map<String, Long> manifests = s3.listSizes(keys.snapshotPrefix());
        Map<String, Long> blobs = s3.listSizes(keys.blobPrefix());
        int[] perTier = new int[Tier.values().length];
        long manifestBytes = 0;
        for (Map.Entry<String, Long> e : manifests.entrySet()) {
            var ref = keys.parseManifestKey(e.getKey());
            if (ref.isPresent()) {
                perTier[ref.get().tier().ordinal()]++;
                manifestBytes += e.getValue();
            }
        }
        long blobBytes = 0;
        for (long v : blobs.values()) blobBytes += v;
        Properties p = loadStats();
        StringBuilder sb = new StringBuilder("McBackup statistics\n");
        sb.append("In the bucket now:\n");
        for (Tier t : Tier.values()) sb.append("  ").append(t.id).append(" backups: ").append(perTier[t.ordinal()]).append('\n');
        sb.append("  data files: ").append(blobs.size()).append("\n");
        sb.append("  STORAGE USED: ").append(mb(blobBytes + manifestBytes))
                .append(" (data ").append(mb(blobBytes)).append(" + backup lists ").append(mb(manifestBytes)).append(")\n");
        sb.append("Since statistics started").append(p.getProperty("since") == null ? " (no backup finished yet)" : " (" + p.getProperty("since") + ")").append(":\n");
        long taken = 0;
        for (Tier t : Tier.values()) taken += num(p, "taken_" + t.id);
        sb.append("  backups taken: ").append(taken).append(" (hourly ").append(num(p, "taken_hourly"))
                .append(", daily ").append(num(p, "taken_daily")).append(", weekly ").append(num(p, "taken_weekly")).append(")\n");
        sb.append("  backups deleted: ").append(num(p, "snapshotsDeleted")).append('\n');
        sb.append("  data uploaded: ").append(mb(num(p, "bytesUploaded"))).append('\n');
        sb.append("  data files removed: ").append(num(p, "blobsDeleted")).append(" (freed ").append(mb(num(p, "bytesFreed"))).append(")");
        return sb.toString();
    }

    // ------------------------------------------------------------------ persisted schedule state

    private Path stateFile() {
        return cfg.dataDir.resolve("state").resolve("schedule.properties");
    }

    private Schedule.State loadState() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(stateFile())) {
            p.load(in);
        } catch (IOException e) {
            return Schedule.State.empty();
        }
        try {
            String d = p.getProperty("lastDaily");
            String w = p.getProperty("lastWeekly");
            return new Schedule.State(p.getProperty("lastHourly"),
                    d == null ? null : LocalDate.parse(d), w == null ? null : LocalDate.parse(w));
        } catch (RuntimeException e) {
            return Schedule.State.empty();
        }
    }

    private void saveState() {
        Properties p = new Properties();
        Schedule.State s = state;
        if (s.lastHourlyKey() != null) p.setProperty("lastHourly", s.lastHourlyKey());
        if (s.lastDaily() != null) p.setProperty("lastDaily", s.lastDaily().toString());
        if (s.lastWeekly() != null) p.setProperty("lastWeekly", s.lastWeekly().toString());
        try {
            Files.createDirectories(stateFile().getParent());
            try (OutputStream out = Files.newOutputStream(stateFile())) {
                p.store(out, "McBackup schedule state");
            }
        } catch (IOException e) {
            reportFailure("could not save schedule state", e);
        }
    }
}
