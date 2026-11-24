package io.mcbackup;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper plugin shell. All real work lives in the other classes; this only wires up config, the
 * scheduler, console commands (save-all / save-off / save-on) and /mcbackup.
 */
public final class McBackupPlugin extends JavaPlugin {

    private static final List<String> SUBCOMMANDS = List.of("now", "status", "stats", "list", "verify", "restore", "test", "cancel", "reload");

    private volatile BackupService service;
    private volatile ZoneId zone = ZoneId.of("Asia/Kolkata");
    private volatile boolean savingPaused;
    private volatile String configProblem;
    private ScheduledExecutorService scheduler;
    private ExecutorService helpers;

    private volatile List<String> beforeCommands = List.of();
    private volatile List<String> afterCommands = List.of();
    private volatile boolean notifyEnabled = true;
    private volatile java.util.Set<String> notifyAdmins = java.util.Set.of();
    private volatile String notifyPublicMsg = "";
    private volatile String notifyAdminMsg = "";

    private final SnapshotEngine.Hooks hooks = new SnapshotEngine.Hooks() {
        @Override
        public void starting(Tier tier, long changedBytes) {
            announce(tier, changedBytes);
        }

        @Override
        public void beforeCopy() throws Exception {
            try {
                // before-commands (e.g. "arkaeco flush") run first so plugin databases are fully on disk
                List<String> cmds = new ArrayList<>(beforeCommands);
                cmds.add("save-all flush");
                cmds.add("save-off");
                runOnMainThread(cmds.toArray(new String[0]));
                savingPaused = true;
            } catch (Exception e) {
                try {
                    runOnMainThread("save-on"); // best effort: never leave autosave off
                } catch (Exception ignored) {
                    // nothing more we can do
                }
                throw e;
            }
        }

        @Override
        public void afterCopy() {
            try {
                List<String> cmds = new ArrayList<>();
                cmds.add("save-on");
                cmds.addAll(afterCommands);
                runOnMainThread(cmds.toArray(new String[0]));
                savingPaused = false;
            } catch (Exception e) {
                getLogger().severe("[McBackup] COULD NOT RE-ENABLE AUTOSAVE. Run  /save-on  in the console now. (" + e.getMessage() + ")");
            }
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "McBackup-Scheduler"));
        helpers = Executors.newCachedThreadPool(r -> daemon(r, "McBackup-Helper"));
        rebuild();
        Commands cmds = new Commands();
        var pc = getCommand("mcbackup");
        if (pc != null) {
            pc.setExecutor(cmds);
            pc.setTabCompleter(cmds);
        }
        // first check after 2 minutes so the server finishes starting up before any backup begins
        scheduler.scheduleWithFixedDelay(this::tick, 120, 20, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        BackupService s = service;
        if (s != null) s.cancelRunning();
        if (scheduler != null) scheduler.shutdownNow();
        if (helpers != null) helpers.shutdownNow();
        if (savingPaused) { // we are on the main thread here
            try {
                getServer().dispatchCommand(getServer().getConsoleSender(), "save-on");
            } catch (Throwable ignored) {
                // server is shutting down anyway
            }
            savingPaused = false;
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    }

    private void tick() {
        BackupService s = service;
        if (s == null) return;
        try {
            s.tick(ZonedDateTime.now(zone));
        } catch (Throwable t) {
            getLogger().severe("[McBackup] scheduler error: " + t);
        }
    }

    // ------------------------------------------------------------------ config

    /** (Re)reads config.yml and creates the service. Never throws; problems are reported in the console. */
    private void rebuild() {
        try {
            service = build();
            configProblem = null;
            getLogger().info("[McBackup] enabled. Schedule time zone: " + zone
                    + ". Run /mcbackup test to check the bucket connection.");
        } catch (Exception e) {
            service = null;
            configProblem = e.getMessage();
            getLogger().warning("[McBackup] not active yet: " + e.getMessage()
                    + " - edit plugins/McBackup/config.yml, then run /mcbackup reload");
        }
    }

    private BackupService build() {
        FileConfiguration c = getConfig();
        BackupConfig bc = new BackupConfig();

        notifyEnabled = c.getBoolean("notify.enabled", true);
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String n : c.getStringList("notify.admins")) if (n != null && !n.isBlank()) names.add(n.trim().toLowerCase(Locale.ROOT));
        notifyAdmins = names;
        notifyPublicMsg = c.getString("notify.public-message",
                "&e[Backup] A backup is taking place. You may notice some lag. This is for your safety.");
        notifyAdminMsg = c.getString("notify.admin-message",
                "&e[Backup] A {type} backup of {size} (changed data) is starting.");
        bc.endpoint = c.getString("storage.endpoint", bc.endpoint);
        bc.region = c.getString("storage.region", bc.region);
        bc.bucket = c.getString("storage.bucket", "");
        bc.prefix = c.getString("storage.prefix", bc.prefix);
        bc.accessKey = firstNonBlank(System.getenv("MCBACKUP_ACCESS_KEY"), c.getString("storage.access-key", ""));
        bc.secretKey = firstNonBlank(System.getenv("MCBACKUP_SECRET_KEY"), c.getString("storage.secret-key", ""));

        bc.dataDir = getDataFolder().toPath().toAbsolutePath().normalize();
        String sd = c.getString("backup.server-dir", "");
        bc.serverDir = sd.isBlank() ? bc.dataDir.getParent().getParent() : Path.of(sd).toAbsolutePath().normalize();
        List<String> ex = c.getStringList("backup.exclude");
        if (!ex.isEmpty()) bc.exclude = new ArrayList<>(ex);
        bc.pauseSaving = c.getBoolean("backup.pause-saving", true);
        beforeCommands = cleanCommands(c.getStringList("backup.before-commands"));
        afterCommands = cleanCommands(c.getStringList("backup.after-commands"));

        bc.zone = ZoneId.of(c.getString("schedule.timezone", "Asia/Kolkata"));
        bc.hourlyMinute = c.getInt("schedule.hourly-minute", bc.hourlyMinute);
        bc.dailyTime = LocalTime.parse(c.getString("schedule.daily-time", "00:00"));
        bc.weeklyDay = DayOfWeek.valueOf(c.getString("schedule.weekly-day", "SUNDAY").trim().toUpperCase(Locale.ROOT));
        bc.weeklyTime = LocalTime.parse(c.getString("schedule.weekly-time", "00:30"));

        bc.keepWeekly = c.getInt("retention.keep-weekly", bc.keepWeekly);
        bc.maxHourly = c.getInt("retention.max-hourly", bc.maxHourly);
        bc.maxDaily = c.getInt("retention.max-daily", bc.maxDaily);

        bc.compressionLevel = c.getInt("performance.compression-level", bc.compressionLevel);
        bc.uploadThreads = c.getInt("performance.upload-threads", bc.uploadThreads);
        bc.maxUploadMbps = c.getDouble("performance.max-upload-mbps", 0);
        bc.retryMinutes = c.getInt("performance.retry-minutes", bc.retryMinutes);

        bc.validate();
        zone = bc.zone;
        S3Client s3 = new S3Client(bc.endpoint, bc.region, bc.bucket, bc.accessKey, bc.secretKey, bc.maxUploadMbps);
        return new BackupService(bc, s3, getLogger(), hooks);
    }

    private static List<String> cleanCommands(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s == null) continue;
            String t = s.trim();
            if (t.startsWith("/")) t = t.substring(1);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    // ------------------------------------------------------------------ in-game notices

    private static String color(String s) {
        return s.replace('&', '\u00a7');
    }

    /**
     * Tells staff (names in notify.admins plus server operators) the backup type and size, and everyone else a
     * general "you may notice lag" notice with no details. Never throws.
     */
    private void announce(Tier tier, long changedBytes) {
        if (!notifyEnabled) return;
        try {
            final java.util.Set<String> admins = notifyAdmins;
            final String pub = color(notifyPublicMsg);
            final String adm = color(notifyAdminMsg
                    .replace("{size}", BackupService.mb(changedBytes))
                    .replace("{type}", tier.id));
            getServer().getScheduler().runTask(this, () -> {
                try {
                    java.util.Collection<? extends org.bukkit.entity.Player> online = getServer().getOnlinePlayers();
                    if (online == null) return;
                    for (org.bukkit.entity.Player p : online) {
                        boolean staff = p.isOp() || admins.contains(p.getName().toLowerCase(Locale.ROOT));
                        String m = staff ? adm : pub;
                        if (!m.isBlank()) p.sendMessage(m);
                    }
                } catch (Throwable t) {
                    getLogger().warning("[McBackup] could not send backup notice: " + t);
                }
            });
        } catch (Throwable t) {
            getLogger().warning("[McBackup] could not send backup notice: " + t);
        }
    }

    // ------------------------------------------------------------------ main-thread helpers

    private void runOnMainThread(String... commands) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        getServer().getScheduler().runTask(this, () -> {
            try {
                for (String cmd : commands) {
                    if (!getServer().dispatchCommand(getServer().getConsoleSender(), cmd)) {
                        throw new IllegalStateException("server did not accept the command '" + cmd + "'");
                    }
                }
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        done.get(10, TimeUnit.MINUTES);
    }

    private void reply(CommandSender to, String message) {
        String line = "[McBackup] " + message;
        try {
            getServer().getScheduler().runTask(this, () -> to.sendMessage(line));
        } catch (Throwable t) {
            getLogger().info(line); // plugin is shutting down
        }
    }

    // ------------------------------------------------------------------ /mcbackup

    private final class Commands implements CommandExecutor, TabCompleter {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0) {
                reply(sender, "Usage: /mcbackup <now [hourly|daily|weekly] | status | list | verify <id> | restore <id> | test | cancel | reload>");
                return true;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("reload")) {
                return reload(sender);
            }
            BackupService s = service;
            if (s == null) {
                reply(sender, "Not configured: " + configProblem + ". Edit plugins/McBackup/config.yml and run /mcbackup reload.");
                return true;
            }
            switch (sub) {
                case "now" -> now(sender, s, args);
                case "status" -> reply(sender, (s.runningJob() != null ? "running: " + s.runningJob() + " backup. " : "idle. ")
                        + "Last result: " + s.lastResult());
                case "cancel" -> {
                    s.cancelRunning();
                    reply(sender, "Cancel requested (takes effect between files).");
                }
                case "stats" -> async(sender, s::stats);
                case "list" -> async(sender, () -> {
                    StringBuilder sb = new StringBuilder("Snapshots (UTC):");
                    List<SnapshotRef> all = s.listSnapshots();
                    if (all.isEmpty()) sb.append(" none yet");
                    for (SnapshotRef r : all) sb.append("\n  ").append(r.id());
                    return sb.toString();
                });
                case "verify" -> {
                    if (args.length < 2) {
                        reply(sender, "Usage: /mcbackup verify <hourly|daily|weekly>/<timestamp>  (see /mcbackup list)");
                    } else {
                        async(sender, () -> {
                            SnapshotRef ref = SnapshotRef.parseId(args[1]);
                            List<String> missing = s.verify(ref);
                            return missing.isEmpty() ? "OK: everything needed for " + ref.id() + " is in the bucket"
                                    : "PROBLEM: " + missing.size() + " data file(s) missing for " + ref.id();
                        });
                    }
                }
                case "restore" -> {
                    if (args.length < 2) {
                        reply(sender, "Usage: /mcbackup restore <hourly|daily|weekly>/<timestamp>");
                    } else {
                        async(sender, () -> {
                            SnapshotRef ref = SnapshotRef.parseId(args[1]);
                            Path out = getDataFolder().toPath().resolve("restore").resolve(ref.tier().id + "_" + ref.id().substring(ref.id().indexOf('/') + 1));
                            BackupService.RestoreResult r = s.restore(ref, out);
                            return "Restored " + r.files() + " files to " + r.dir()
                                    + " (live server files were NOT touched). Stop the server and copy what you need over.";
                        });
                    }
                }
                case "test" -> async(sender, s::selfTest);
                default -> reply(sender, "Unknown subcommand. Try /mcbackup for usage.");
            }
            return true;
        }

        private void now(CommandSender sender, BackupService s, String[] args) {
            Tier tier;
            try {
                tier = args.length > 1 ? Tier.parse(args[1]) : Tier.HOURLY;
            } catch (IllegalArgumentException e) {
                reply(sender, e.getMessage());
                return;
            }
            if (s.runningJob() != null) {
                reply(sender, "A " + s.runningJob() + " backup is already running.");
                return;
            }
            reply(sender, "Starting " + tier.id + " backup in the background...");
            scheduler.execute(() -> {
                try {
                    reply(sender, tier.id + " backup finished: " + s.runJob(tier, ZonedDateTime.now(zone)).describe());
                } catch (Exception e) {
                    reply(sender, tier.id + " backup FAILED: " + e.getMessage());
                }
            });
        }

        private boolean reload(CommandSender sender) {
            BackupService s = service;
            if (s != null && s.runningJob() != null) {
                reply(sender, "Cannot reload while a backup is running.");
                return true;
            }
            reloadConfig();
            rebuild();
            reply(sender, service != null ? "Config reloaded." : "Config problem: " + configProblem);
            return true;
        }

        private void async(CommandSender sender, ThrowingSupplier job) {
            helpers.execute(() -> {
                try {
                    reply(sender, job.get());
                } catch (Exception e) {
                    reply(sender, "Failed: " + e.getMessage());
                }
            });
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> out = new ArrayList<>();
            if (args.length == 1) {
                for (String s : SUBCOMMANDS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            } else if (args.length == 2 && args[0].equalsIgnoreCase("now")) {
                for (Tier t : Tier.values()) if (t.id.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(t.id);
            }
            return out;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        String get() throws Exception;
    }
}
