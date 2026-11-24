package io.mcbackup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class TestMain {
    static int passed, failed;

    static void check(boolean ok, String what) {
        if (ok) { passed++; System.out.println("  PASS  " + what); }
        else { failed++; System.out.println("  FAIL  " + what); }
    }

    static void section(String s) { System.out.println("\n== " + s); }

    public static void main(String[] args) throws Exception {
        try { testSigV4(); } catch (Throwable t) { crash("sigv4", t); }
        try { testRetention(); } catch (Throwable t) { crash("retention", t); }
        try { testSchedule(); } catch (Throwable t) { crash("schedule", t); }
        try { testManifest(); } catch (Throwable t) { crash("manifest", t); }
        try { testEndToEnd(); } catch (Throwable t) { crash("e2e", t); }
        try { testPluginShell(); } catch (Throwable t) { crash("plugin", t); }
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void crash(String n, Throwable t) { failed++; System.out.println("  CRASH in " + n + ": " + t); t.printStackTrace(System.out); }

    // ------------------------------------------------------------------ SigV4 vs Amazon's published examples
    static void testSigV4() {
        section("SigV4 signing vs AWS documentation examples");
        String empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String ak = "AKIAIOSFODNN7EXAMPLE", sk = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        Instant t = Instant.parse("2013-05-24T00:00:00Z");

        TreeMap<String, String> h = new TreeMap<>();
        h.put("host", "examplebucket.s3.amazonaws.com"); h.put("range", "bytes=0-9");
        h.put("x-amz-content-sha256", empty); h.put("x-amz-date", "20130524T000000Z");
        String a = S3Client.authorization("GET", "/test.txt", Map.of(), h, empty, ak, sk, "us-east-1", "s3", t);
        check(a.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"), "GET object with Range header");

        h = new TreeMap<>();
        h.put("host", "examplebucket.s3.amazonaws.com"); h.put("x-amz-content-sha256", empty); h.put("x-amz-date", "20130524T000000Z");
        TreeMap<String, String> q = new TreeMap<>(); q.put("max-keys", "2"); q.put("prefix", "J");
        a = S3Client.authorization("GET", "/", q, h, empty, ak, sk, "us-east-1", "s3", t);
        check(a.endsWith("Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"), "list objects with query string");

        String body = "Welcome to Amazon S3.";
        String bodySha = S3Client.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
        h = new TreeMap<>();
        h.put("date", "Fri, 24 May 2013 00:00:00 GMT"); h.put("host", "examplebucket.s3.amazonaws.com");
        h.put("x-amz-content-sha256", bodySha); h.put("x-amz-date", "20130524T000000Z"); h.put("x-amz-storage-class", "REDUCED_REDUNDANCY");
        a = S3Client.authorization("PUT", "/" + S3Client.enc("test$file.text", true), Map.of(), h, bodySha, ak, sk, "us-east-1", "s3", t);
        check(a.endsWith("Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"), "PUT object with body + special character in key");
        check(S3Client.enc("a b/c~d", true).equals("a%20b/c~d") && S3Client.enc("a/b", false).equals("a%2Fb"), "URI encoding rules");
    }

    // ------------------------------------------------------------------ retention
    static SnapshotRef ref(Tier t, String iso) { return new SnapshotRef(t, Instant.parse(iso)); }

    static void testRetention() {
        section("Retention rules");
        BackupConfig c = new BackupConfig();
        List<SnapshotRef> ex = new ArrayList<>();
        for (int h = 0; h < 24; h++) ex.add(ref(Tier.HOURLY, String.format("2026-11-24T%02d:05:00Z", h)));
        ex.add(ref(Tier.DAILY, "2026-11-23T18:30:00Z"));
        SnapshotRef daily = ref(Tier.DAILY, "2026-11-25T00:00:00Z");
        ex.add(daily);
        ex.add(ref(Tier.HOURLY, "2026-11-25T00:05:00Z")); // newer than the daily: must survive
        List<SnapshotRef> del = Retention.plan(Tier.DAILY, daily, ex, c);
        check(del.size() == 24 && del.stream().allMatch(s -> s.tier() == Tier.HOURLY), "daily removes the 24 older hourlies");
        check(!del.contains(ref(Tier.HOURLY, "2026-11-25T00:05:00Z")), "hourly newer than the daily is kept");
        check(!del.contains(ref(Tier.DAILY, "2026-11-23T18:30:00Z")), "older daily is NOT removed by a daily");

        List<SnapshotRef> ex2 = new ArrayList<>();
        for (int d = 0; d < 7; d++) ex2.add(ref(Tier.DAILY, "2026-11-" + (17 + d) + "T18:30:00Z"));
        SnapshotRef weekly = ref(Tier.WEEKLY, "2026-11-24T19:00:00Z");
        ex2.add(weekly);
        ex2.add(ref(Tier.WEEKLY, "2026-11-17T19:00:00Z"));
        del = Retention.plan(Tier.WEEKLY, weekly, ex2, c);
        check(del.size() == 7 && del.stream().allMatch(s -> s.tier() == Tier.DAILY), "weekly removes all 7 dailies, keeps weeklies");

        c.keepWeekly = 2;
        List<SnapshotRef> ex3 = new ArrayList<>();
        for (int w = 1; w <= 4; w++) ex3.add(ref(Tier.WEEKLY, "2026-0" + w + "-01T00:00:00Z"));
        del = Retention.plan(Tier.WEEKLY, ex3.get(3), ex3, c);
        check(del.size() == 2 && del.contains(ex3.get(0)) && del.contains(ex3.get(1)), "keep-weekly=2 deletes the two oldest weeklies");

        c = new BackupConfig(); c.maxHourly = 3;
        List<SnapshotRef> ex4 = new ArrayList<>();
        for (int h = 0; h < 6; h++) ex4.add(ref(Tier.HOURLY, String.format("2026-11-24T%02d:05:00Z", h)));
        del = Retention.plan(Tier.HOURLY, ex4.get(5), ex4, c);
        check(del.size() == 3 && !del.contains(ex4.get(5)) && del.contains(ex4.get(0)), "safety cap trims oldest hourlies only");
        check(Retention.plan(Tier.HOURLY, ex4.get(5), List.of(ex4.get(5)), new BackupConfig()).isEmpty(), "a lone snapshot is never deleted");
    }

    // ------------------------------------------------------------------ schedule
    static void testSchedule() {
        section("Schedule rules (Asia/Kolkata)");
        BackupConfig c = new BackupConfig();
        ZoneId z = c.zone;
        Schedule.State s = Schedule.State.empty();
        ZonedDateTime tue11 = ZonedDateTime.of(2026, 11, 24, 11, 0, 0, 0, z);
        check(Schedule.due(tue11, s, c) == Tier.DAILY, "fresh install: first job is a daily");
        s = Schedule.after(s, Tier.DAILY, tue11);
        check(Schedule.due(tue11.withMinute(6), s, c) == null, "no second backup in the same hour after a daily");
        check(Schedule.due(tue11.withHour(12).withMinute(4), s, c) == null, "before :05 nothing is due");
        check(Schedule.due(tue11.withHour(12).withMinute(5), s, c) == Tier.HOURLY, "hourly due at HH:05");
        s = Schedule.after(s, Tier.HOURLY, tue11.withHour(12).withMinute(5));
        check(Schedule.due(tue11.withHour(12).withMinute(40), s, c) == null, "hourly runs once per hour");
        ZonedDateTime wed0 = ZonedDateTime.of(2026, 11, 25, 0, 0, 0, 0, z);
        check(Schedule.due(wed0, s, c) == Tier.DAILY, "daily due at midnight");
        ZonedDateTime sun0 = ZonedDateTime.of(2026, 11, 29, 0, 0, 0, 0, z);
        s = Schedule.after(s, Tier.DAILY, sun0);
        check(Schedule.due(sun0.withMinute(10), s, c) == null, "weekly not before 00:30");
        check(Schedule.due(sun0.withMinute(30), s, c) == Tier.WEEKLY, "weekly due Sunday 00:30");
        s = Schedule.after(s, Tier.WEEKLY, sun0.withMinute(30));
        check(Schedule.due(sun0.withHour(1).withMinute(5), s, c) == Tier.HOURLY, "normal hourly resumes after weekly");
        ZonedDateTime mon = ZonedDateTime.of(2026, 11, 30, 0, 30, 0, 0, z);
        check(Schedule.due(mon, s, c) == Tier.DAILY, "Monday: daily again, never a weekly");
        s = Schedule.after(s, Tier.DAILY, mon);
        check(Schedule.due(mon.withHour(5).withMinute(30), s, c) == Tier.HOURLY, "Monday hourly");
        // server was down over midnight and starts at 14:00
        Schedule.State old = new Schedule.State("2026-11-23T22", LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 22));
        check(Schedule.due(ZonedDateTime.of(2026, 11, 24, 14, 20, 0, 0, z), old, c) == Tier.DAILY, "catch-up daily after downtime");
    }

    // ------------------------------------------------------------------ manifest
    static void testManifest() throws IOException {
        section("Manifest format");
        List<Manifest.Entry> es = List.of(
                new Manifest.Entry("world/region/r.0.0.mca", 123, 456, "a".repeat(64)),
                new Manifest.Entry("odd name\twith tab%and\nnewline", 1, 2, "b".repeat(64)));
        Manifest m = new Manifest(Tier.DAILY, Instant.parse("2026-11-24T10:00:00Z"), es);
        Manifest back = Manifest.parse(m.toGzipBytes());
        check(back.entries.equals(es) && back.tier == Tier.DAILY, "round trip incl. tab/newline/percent in path");
        byte[] gz = m.toGzipBytes();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream o = new GZIPOutputStream(bos)) { o.write("garbage".getBytes()); }
        boolean threw = false;
        try { Manifest.parse(bos.toByteArray()); } catch (IOException e) { threw = true; }
        check(threw, "garbage manifest rejected");
        // truncated manifest (missing #end line) must be rejected
        String text; try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) { text = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        text = text.substring(0, text.indexOf("#end"));
        bos = new ByteArrayOutputStream();
        try (GZIPOutputStream o = new GZIPOutputStream(bos)) { o.write(text.getBytes(StandardCharsets.UTF_8)); }
        threw = false;
        try { Manifest.parse(bos.toByteArray()); } catch (IOException e) { threw = true; }
        check(threw, "truncated manifest rejected");
    }

    // ------------------------------------------------------------------ end to end
    static void write(Path root, String rel, byte[] data, long ageSeconds) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, data);
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - ageSeconds * 1000));
    }

    static byte[] blob(long seed, int size) {
        byte[] b = new byte[size]; Random r = new Random(seed);
        for (int i = 0; i < size; i += 64) { byte v = (byte) r.nextInt(256); for (int j = i; j < Math.min(size, i + 64); j++) b[j] = (byte) (v ^ (j & 7)); }
        return b;
    }

    static final class Recorder implements SnapshotEngine.Hooks {
        final List<String> calls = new CopyOnWriteArrayList<>();
        public void beforeCopy() { calls.add("before"); }
        public void afterCopy() { calls.add("after"); }
    }

    static String gunzipText(byte[] b) throws IOException { try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) { return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1); } }

    static void pause() throws InterruptedException { Thread.sleep(1100); } // manifests are named by second

    static void testEndToEnd() throws Exception {
        section("End to end against the signature-checking mock bucket");
        MockS3 s3m = new MockS3(0);
        Path root = Files.createTempDirectory("mcbackup-e2e");
        Path server = root.resolve("server");
        Path data = server.resolve("plugins/McBackup");
        write(server, "server.properties", "motd=hello".getBytes(), 3600);
        write(server, "world/level.dat", blob(1, 2000), 3600);
        write(server, "world/region/r.0.0.mca", blob(2, 3_000_000), 3600);
        write(server, "world/region/r.0.1.mca", blob(3, 1_000_000), 3600);
        write(server, "plugins/Other/config.yml", "a: b\n".getBytes(), 3600);
        write(server, "logs/latest.log", "LOGLINE".getBytes(), 3600);
        write(server, "cache/x.bin", "CACHE".getBytes(), 3600);
        write(server, "world/session.lock", "LOCK".getBytes(), 3600);
        write(server, "plugins/McBackup/config.yml", "secret-key: SECRETCONFIGMARKER".getBytes(), 3600);

        BackupConfig cfg = new BackupConfig();
        cfg.endpoint = s3m.endpoint(); cfg.bucket = MockS3.BUCKET; cfg.region = MockS3.REGION;
        cfg.accessKey = MockS3.ACCESS; cfg.secretKey = MockS3.SECRET; cfg.prefix = "mc/test";
        cfg.serverDir = server; cfg.dataDir = data; cfg.keepWeekly = 2; cfg.uploadThreads = 2;
        s3m.pageSize = 2; // force several listing pages
        S3Client s3 = new S3Client(cfg.endpoint, cfg.region, cfg.bucket, cfg.accessKey, cfg.secretKey, 0);
        s3.retryBaseMillis = 5;
        Recorder rec = new Recorder();
        BackupService svc = new BackupService(cfg, s3, Logger.getLogger("test"), rec);
        ZonedDateTime now = ZonedDateTime.now(cfg.zone);

        BackupService.JobSummary j1 = svc.runJob(Tier.HOURLY, now);
        check(j1.snapshot().files() == 5, "first backup covers exactly the 5 wanted files (got " + j1.snapshot().files() + ")");
        check(j1.snapshot().uploadedBlobs() == 5, "first backup uploads 5 blobs");
        check(rec.calls.equals(List.of("before", "after")), "autosave paused then resumed: " + rec.calls);
        long compressed = j1.snapshot().uploadedBytes();
        check(compressed < 1_500_000, "data is compressed on upload (" + compressed + " bytes for ~4 MB of files)");
        boolean leaked = false;
        for (Map.Entry<String, byte[]> e : s3m.objects.entrySet()) {
            String txt = e.getKey().endsWith(".gz") ? gunzipText(e.getValue()) : new String(e.getValue(), StandardCharsets.ISO_8859_1);
            if (txt.contains("SECRETCONFIGMARKER") || txt.contains("LOGLINE") || txt.contains("CACHE") || txt.contains("logs/latest.log")) leaked = true;
        }
        check(!leaked, "excluded files and the plugin's own credentials are never uploaded");

        pause();
        rec.calls.clear();
        write(server, "world/region/r.0.0.mca", blob(22, 3_000_000), 0); // changed just now
        BackupService.JobSummary j2 = svc.runJob(Tier.HOURLY, now.plusHours(1));
        check(j2.snapshot().uploadedBlobs() == 1, "only the changed file is uploaded (" + j2.snapshot().uploadedBlobs() + ")");
        pause();
        BackupService.JobSummary j3 = svc.runJob(Tier.HOURLY, now.plusHours(2));
        check(j3.snapshot().uploadedBlobs() == 0, "unchanged world uploads nothing");
        check(svc.listSnapshots().stream().filter(s -> s.tier() == Tier.HOURLY).count() == 3, "three hourly snapshots exist");
        check(s3m.badSignatures.get() == 0 && s3m.badPayloadHashes.get() == 0, "every request had a valid signature and payload hash");

        // failure: manifest upload fails -> old snapshots must NOT be deleted, autosave must be restored
        pause();
        write(server, "world/level.dat", blob(99, 2000), 0);
        rec.calls.clear();
        s3m.failures.add(new MockS3.Fail(k -> k.startsWith("PUT") && k.contains(".manifest.gz"), 500, 100));
        boolean threw = false;
        try { svc.runJob(Tier.DAILY, now.plusHours(3)); } catch (Exception e) { threw = true; }
        check(threw, "daily fails when the manifest cannot be committed");
        check(svc.listSnapshots().stream().filter(s -> s.tier() == Tier.HOURLY).count() == 3, "hourlies untouched after failed daily");
        check(rec.calls.equals(List.of("before", "after")), "autosave resumed even though the backup failed");
        s3m.failures.clear();

        // transient error: one 503 on a blob upload is retried
        pause();
        write(server, "plugins/Other/config.yml", "a: changed\n".getBytes(), 0);
        s3m.failures.add(new MockS3.Fail(k -> k.startsWith("PUT") && k.contains("blobs/"), 503, 1));
        int before = s3m.injectedFailures.get();
        BackupService.JobSummary jr = svc.runJob(Tier.HOURLY, now.plusHours(4));
        check(s3m.injectedFailures.get() == before + 1 && jr.snapshot().uploadedBlobs() >= 1, "transient 503 was retried and the backup succeeded");
        s3m.failures.clear();

        // daily: replaces hourlies, cleans unused blobs (including the ones from the failed run)
        pause();
        BackupService.JobSummary d = svc.runJob(Tier.DAILY, now.plusHours(5));
        check(BackupService.mb(999L * 1_048_576).equals("999.0 MB") && BackupService.mb(1000L * 1_048_576).equals("0.98 GB")
                && BackupService.mb(5L * 1_073_741_824L).equals("5.00 GB") && BackupService.mb(0).equals("0.0 MB"), "sizes switch from MB to GB at 1000 MB");
        String st = svc.stats();
        check(st.contains("backups taken: 5") && st.contains("daily 1") && st.contains("STORAGE USED") && st.contains("daily backups: 1")
                && st.contains("hourly backups: 0") && !st.contains("backups deleted: 0"), "stats report counts taken/deleted/storage: " + st.replace('\n', '|'));
        try {
            String lg = java.nio.file.Files.readString(data.resolve("backup-log.md"));
            check(lg.contains("# McBackup log") && lg.contains("| hourly | OK |") && lg.contains("| daily | FAILED |")
                    && lg.contains("| daily | OK |"), "backup-log.md has OK, FAILED and daily rows: " + lg.lines().count() + " lines");
        } catch (Exception e) { check(false, "backup-log.md readable: " + e); }
        List<SnapshotRef> snaps = svc.listSnapshots();
        check(snaps.size() == 1 && snaps.get(0).tier() == Tier.DAILY, "after daily only the daily remains: " + snaps);
        check(d.deletedSnapshots() == 4 && d.deletedBlobs() > 0, "old hourlies and now-unused data removed (" + d.deletedSnapshots() + "/" + d.deletedBlobs() + ")");
        long blobCount = s3m.objects.keySet().stream().filter(k -> k.contains("/blobs/")).count();
        check(blobCount == 5, "bucket holds exactly the 5 blobs the daily needs (" + blobCount + ")");

        // restore must reproduce the files byte for byte
        Path out = root.resolve("restored");
        BackupService.RestoreResult rr = svc.restore(snaps.get(0), out);
        boolean same = rr.files() == 5;
        for (String rel : List.of("server.properties", "world/level.dat", "world/region/r.0.0.mca", "world/region/r.0.1.mca", "plugins/Other/config.yml")) {
            same &= Arrays.equals(Files.readAllBytes(server.resolve(rel)), Files.readAllBytes(out.resolve(rel)));
        }
        check(same, "restore reproduces every file byte for byte");
        check(Files.getLastModifiedTime(out.resolve("world/level.dat")).toMillis() == Files.getLastModifiedTime(server.resolve("world/level.dat")).toMillis(), "file timestamps restored");
        check(svc.verify(snaps.get(0)).isEmpty(), "verify reports a healthy snapshot");

        // weekly with keep-weekly=2
        for (int i = 0; i < 3; i++) { pause(); svc.runJob(Tier.WEEKLY, now.plusDays(i + 1)); }
        snaps = svc.listSnapshots();
        check(snaps.size() == 2 && snaps.stream().allMatch(s -> s.tier() == Tier.WEEKLY), "weekly replaced the daily; only 2 newest weeklies kept: " + snaps);
        check(s3m.objects.keySet().stream().filter(k -> k.contains("/blobs/")).count() == 5, "no leftover data after weekly cleanup");

        // corruption / tampering
        String victim = s3m.objects.keySet().stream().filter(k -> k.contains("/blobs/")).sorted().findFirst().get();
        byte[] good = s3m.objects.get(victim);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream o = new GZIPOutputStream(bos)) { o.write("tampered".getBytes()); }
        s3m.objects.put(victim, bos.toByteArray());
        threw = false;
        try { svc.restore(snaps.get(0), root.resolve("restored-bad")); } catch (IOException e) { threw = e.getMessage().contains("Checksum"); }
        check(threw, "tampered data is detected by checksum during restore");
        s3m.objects.remove(victim);
        check(svc.verify(snaps.get(0)).size() == 1, "verify detects a missing data file");
        s3m.objects.put(victim, good);

        // path traversal in a hostile manifest
        String sha = victim.substring(victim.lastIndexOf('/') + 1, victim.length() - 3);
        Manifest evil = new Manifest(Tier.DAILY, Instant.parse("2020-01-01T00:00:00Z"),
                List.of(new Manifest.Entry("../escaped.txt", 1, 1, sha)));
        s3m.objects.put("mc/test/snapshots/daily/20200101T000000Z.manifest.gz", evil.toGzipBytes());
        threw = false;
        try { svc.restore(SnapshotRef.parseId("daily/20200101T000000Z"), root.resolve("restored-evil/inner")); } catch (IOException e) { threw = e.getMessage().contains("unsafe"); }
        check(threw && !Files.exists(root.resolve("restored-evil/escaped.txt")), "path traversal in a manifest is refused");

        check(s3m.badSignatures.get() == 0 && s3m.badPayloadHashes.get() == 0, "no bad signatures or payload hashes in the whole run");

        // bad credentials give a clear error and nothing is written
        S3Client bad = new S3Client(cfg.endpoint, cfg.region, cfg.bucket, cfg.accessKey, "wrong-secret", 0);
        threw = false;
        try { bad.list("x"); } catch (S3Client.S3Exception e) { threw = e.status == 403; }
        check(threw, "wrong secret key -> HTTP 403 (not retried forever)");

        // cancel
        AtomicBoolean cancel = new AtomicBoolean(true);
        threw = false;
        try { new SnapshotEngine(cfg, s3, new Keys(cfg.keyRoot()), Logger.getLogger("t")).run(Tier.HOURLY, rec, cancel); } catch (IOException e) { threw = e.getMessage().contains("cancel"); }
        check(threw, "cancel flag stops a backup");
        s3m.stop();
    }

    // ------------------------------------------------------------------ plugin shell with stand-in Bukkit
    static void testPluginShell() throws Exception {
        section("Plugin shell (commands, save-all/save-off/save-on ordering)");
        MockS3 s3m = new MockS3(0);
        Path root = Files.createTempDirectory("mcbackup-plugin");
        Path server = root.resolve("server");
        write(server, "world/level.dat", blob(5, 3000), 3600);
        write(server, "server.properties", "x=y".getBytes(), 3600);
        Path data = server.resolve("plugins/McBackup");
        Files.createDirectories(data);

        List<String> dispatched = new CopyOnWriteArrayList<>();
        org.bukkit.command.ConsoleCommandSender console = (org.bukkit.command.ConsoleCommandSender) Proxy.newProxyInstance(
                TestMain.class.getClassLoader(), new Class<?>[]{org.bukkit.command.ConsoleCommandSender.class}, (p, m, a) -> null);
        org.bukkit.scheduler.BukkitScheduler sched = (org.bukkit.scheduler.BukkitScheduler) Proxy.newProxyInstance(
                TestMain.class.getClassLoader(), new Class<?>[]{org.bukkit.scheduler.BukkitScheduler.class},
                (p, m, a) -> { if (m.getName().equals("runTask")) ((Runnable) a[1]).run(); return null; });
        Map<String, List<String>> got = new java.util.concurrent.ConcurrentHashMap<>();
        List<org.bukkit.entity.Player> players = new ArrayList<>();
        for (Object[] pl : new Object[][]{{"Boss", false}, {"OpGuy", true}, {"Steve", false}}) {
            String nm = (String) pl[0]; boolean op = (Boolean) pl[1];
            got.put(nm, new CopyOnWriteArrayList<>());
            players.add((org.bukkit.entity.Player) Proxy.newProxyInstance(TestMain.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.entity.Player.class}, (p, m, a) -> switch (m.getName()) {
                        case "getName" -> nm;
                        case "isOp" -> op;
                        case "sendMessage" -> { got.get(nm).add((String) a[0]); yield null; }
                        default -> null;
                    }));
        }
        org.bukkit.plugin.java.JavaPlugin.TEST_SERVER = (org.bukkit.Server) Proxy.newProxyInstance(
                TestMain.class.getClassLoader(), new Class<?>[]{org.bukkit.Server.class}, (p, m, a) -> switch (m.getName()) {
                    case "getScheduler" -> sched;
                    case "getConsoleSender" -> console;
                    case "dispatchCommand" -> { dispatched.add((String) a[1]); yield true; }
                    case "getOnlinePlayers" -> players;
                    default -> null;
                });
        org.bukkit.plugin.java.JavaPlugin.TEST_DATA = data.toFile();
        Map<String, Object> v = org.bukkit.plugin.java.JavaPlugin.TEST_CONFIG.values;
        v.put("storage.endpoint", s3m.endpoint()); v.put("storage.bucket", MockS3.BUCKET); v.put("storage.region", MockS3.REGION);
        v.put("storage.access-key", MockS3.ACCESS); v.put("storage.secret-key", MockS3.SECRET); v.put("storage.prefix", "plug");
        v.put("notify.admins", new ArrayList<>(List.of("boss")));

        McBackupPlugin plugin = new McBackupPlugin();
        plugin.onEnable();
        org.bukkit.command.PluginCommand pc = org.bukkit.plugin.java.JavaPlugin.TEST_COMMANDS.get("mcbackup");
        List<String> msgs = Collections.synchronizedList(new ArrayList<>());
        org.bukkit.command.CommandSender sender = msg -> msgs.add(msg);

        pc.executor.onCommand(sender, pc, "mcbackup", new String[]{"test"});
        waitFor(msgs, "OK"); check(msgs.stream().anyMatch(m -> m.contains("upload, download and delete all worked")), "/mcbackup test reaches the bucket");
        msgs.clear();
        pc.executor.onCommand(sender, pc, "mcbackup", new String[]{"now", "hourly"});
        waitFor(msgs, "finished");
        check(msgs.stream().anyMatch(m -> m.contains("hourly backup finished")), "/mcbackup now hourly completes: " + msgs);
        check(got.get("Boss").size() == 1 && got.get("Boss").get(0).contains("hourly backup of") && got.get("Boss").get(0).contains("MB"),
                "listed admin gets type + size: " + got.get("Boss"));
        check(got.get("OpGuy").size() == 1 && got.get("OpGuy").get(0).contains("hourly backup of"), "server OP gets type + size: " + got.get("OpGuy"));
        check(got.get("Steve").size() == 1 && got.get("Steve").get(0).contains("lag") && got.get("Steve").get(0).contains("safety")
                && !got.get("Steve").get(0).contains("MB") && !got.get("Steve").get(0).toLowerCase().contains("hourly"),
                "normal player gets general lag notice with no details: " + got.get("Steve"));
        check(dispatched.equals(List.of("save-all flush", "save-off", "save-on")), "console commands in the right order: " + dispatched);
        msgs.clear();
        pc.executor.onCommand(sender, pc, "mcbackup", new String[]{"list"});
        waitFor(msgs, "hourly/");
        String id = msgs.get(0).lines().filter(l -> l.contains("hourly/")).findFirst().get().trim();
        check(id.matches("hourly/\\d{8}T\\d{6}Z"), "/mcbackup list shows the snapshot id: " + id);
        msgs.clear();
        pc.executor.onCommand(sender, pc, "mcbackup", new String[]{"verify", id});
        waitFor(msgs, "OK"); check(msgs.get(0).startsWith("[McBackup] OK"), "/mcbackup verify healthy");
        msgs.clear();
        pc.executor.onCommand(sender, pc, "mcbackup", new String[]{"restore", id});
        waitFor(msgs, "Restored");
        Path restored = data.resolve("restore").resolve("hourly_" + id.substring(id.indexOf('/') + 1));
        check(Files.isRegularFile(restored.resolve("world/level.dat")) && Files.exists(server.resolve("world/level.dat")), "/mcbackup restore writes to plugins/McBackup/restore, not over the live files");
        msgs.clear();
        pc.executor.onCommand(sender, pc, "mcbackup", new String[]{"now", "bogus"});
        waitFor(msgs, "Unknown");
        check(msgs.get(0).contains("Unknown tier"), "bad tier name gives a clear message");
        check(pc.completer.onTabComplete(sender, pc, "mcbackup", new String[]{"no"}).equals(List.of("now")), "tab completion");

        // unconfigured plugin must not crash and must explain itself
        v.put("storage.bucket", "YOUR-BUCKET");
        msgs.clear();
        pc.executor.onCommand(sender, pc, "mcbackup", new String[]{"reload"});
        waitFor(msgs, "problem");
        check(msgs.get(0).contains("storage.bucket is not set"), "placeholder bucket name is reported clearly");
        msgs.clear();
        pc.executor.onCommand(sender, pc, "mcbackup", new String[]{"now"});
        waitFor(msgs, "Not configured");
        check(msgs.get(0).contains("Not configured"), "commands refuse politely until configured");
        plugin.onDisable();
        s3m.stop();
    }

    static void waitFor(List<String> msgs, String needle) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            synchronized (msgs) { if (msgs.stream().anyMatch(m -> m.contains(needle))) return; }
            Thread.sleep(50);
        }
    }
}
