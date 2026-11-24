package io.mcbackup;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Command line access for disaster recovery (works without a running Minecraft server):
 *   java -jar McBackup.jar list
 *   java -jar McBackup.jar verify hourly/20261124T053500Z
 *   java -jar McBackup.jar restore daily/20261124T183000Z /path/to/output-folder
 *   java -jar McBackup.jar test
 * Settings come from environment variables: MCBACKUP_BUCKET, MCBACKUP_ACCESS_KEY, MCBACKUP_SECRET_KEY,
 * and optionally MCBACKUP_PREFIX (default "minecraft"), MCBACKUP_ENDPOINT, MCBACKUP_REGION.
 */
public final class Cli {
    private Cli() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java -jar McBackup.jar <list|verify <id>|restore <id> <dir>|test>");
            System.out.println("Set MCBACKUP_BUCKET, MCBACKUP_ACCESS_KEY, MCBACKUP_SECRET_KEY (and optionally MCBACKUP_PREFIX).");
            return;
        }
        BackupConfig c = new BackupConfig();
        c.bucket = env("MCBACKUP_BUCKET", "");
        c.accessKey = env("MCBACKUP_ACCESS_KEY", "");
        c.secretKey = env("MCBACKUP_SECRET_KEY", "");
        c.prefix = env("MCBACKUP_PREFIX", c.prefix);
        c.endpoint = env("MCBACKUP_ENDPOINT", c.endpoint);
        c.region = env("MCBACKUP_REGION", c.region);
        c.serverDir = Path.of(".");
        c.dataDir = Path.of(System.getProperty("java.io.tmpdir"), "mcbackup-cli");
        c.validate();

        S3Client s3 = new S3Client(c.endpoint, c.region, c.bucket, c.accessKey, c.secretKey, 0);
        BackupService svc = new BackupService(c, s3, Logger.getLogger("McBackup"), SnapshotEngine.Hooks.NOOP);
        switch (args[0]) {
            case "list" -> {
                for (SnapshotRef r : svc.listSnapshots()) System.out.println(r.id());
            }
            case "verify" -> {
                need(args, 2);
                List<String> missing = svc.verify(SnapshotRef.parseId(args[1]));
                System.out.println(missing.isEmpty() ? "OK: all data for " + args[1] + " is present"
                        : "PROBLEM: " + missing.size() + " data file(s) missing for " + args[1]);
            }
            case "restore" -> {
                need(args, 3);
                BackupService.RestoreResult r = svc.restore(SnapshotRef.parseId(args[1]), Path.of(args[2]));
                System.out.println("Restored " + r.files() + " files (" + r.bytes() + " bytes) to " + r.dir());
            }
            case "test" -> System.out.println(svc.selfTest());
            default -> System.out.println("Unknown command: " + args[0]);
        }
    }

    private static void need(String[] a, int n) {
        if (a.length < n) throw new IllegalArgumentException("Missing arguments; run without arguments for usage");
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
