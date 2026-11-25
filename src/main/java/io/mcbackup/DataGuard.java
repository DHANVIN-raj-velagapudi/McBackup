package io.mcbackup;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Keeps a plugin's saved data safe across upgrades and downgrades.
 *
 * <p>The plugin folder holds {@code data-version.properties}: the plugin version that last ran and the data format
 * number. At every start, BEFORE any data is loaded:
 * <ul>
 *   <li>same version: nothing happens;</li>
 *   <li>a different version (upgrade or downgrade): a full snapshot of the data files is copied to
 *       {@code data-backups/} first (the last {@value #KEEP} are kept), then the new version is recorded;</li>
 *   <li>data written in a NEWER data format than this jar understands: the plugin refuses to start, so an old jar
 *       can never rewrite (and damage) data it does not understand. Install the newer jar again, or restore a snapshot.</li>
 * </ul>
 */
public final class DataGuard {
    static final String FILE = "data-version.properties";
    static final String BACKUPS = "data-backups";
    static final int KEEP = 5;

    private DataGuard() {
    }

    /** Result of the start-up check. */
    public record Outcome(boolean proceed, String fromVersion, int fromFormat, File snapshot) {
    }

    /**
     * @param dataFolder      the plugin's data folder
     * @param version         the running plugin version
     * @param supportedFormat the data format this jar reads and writes (raise it when a release changes saved data)
     */
    public static Outcome check(File dataFolder, String version, int supportedFormat, Logger log) {
        if (dataFolder == null) return new Outcome(true, "", supportedFormat, null);
        File stamp = new File(dataFolder, FILE);
        String stored = "";
        int storedFormat = supportedFormat;
        boolean hadStamp = stamp.isFile();
        if (hadStamp) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(stamp.toPath())) {
                p.load(in);
                stored = p.getProperty("plugin-version", "").trim();
                storedFormat = Integer.parseInt(p.getProperty("data-format", String.valueOf(supportedFormat)).trim());
            } catch (IOException | RuntimeException e) {
                log.warning("Could not read " + FILE + " (" + e.getMessage() + "); treating the data as unknown and backing it up.");
                stored = "";
                hadStamp = false;
            }
        }

        if (storedFormat > supportedFormat) {
            log.severe("=========================================================================");
            log.severe("The saved data was written by a NEWER version (" + (stored.isEmpty() ? "?" : stored)
                    + ", data format " + storedFormat + ") than this jar (" + version + ", data format "
                    + supportedFormat + ").");
            log.severe("To protect your data this plugin will NOT start. Install the newer jar again, or");
            log.severe("restore the data files from " + BACKUPS + "/ (made before the upgrade) if you want to downgrade.");
            log.severe("=========================================================================");
            return new Outcome(false, stored, storedFormat, null);
        }

        if (hadStamp && stored.equals(version)) return new Outcome(true, stored, storedFormat, null);

        File snapshot = null;
        boolean hasData = hasData(dataFolder);
        if (hasData) {
            try {
                snapshot = snapshot(dataFolder, stored.isEmpty() ? "unknown" : stored, version);
            } catch (IOException e) {
                log.severe("Could not save a safety snapshot of the data before the version change: " + e.getMessage());
                if (storedFormat < supportedFormat) {
                    log.severe("The data must be converted to a newer format, so this plugin will NOT start without a snapshot.");
                    return new Outcome(false, stored, storedFormat, null);
                }
            }
        }

        String from = stored.isEmpty() ? "an unknown version" : stored;
        if (hasData || !stored.isEmpty()) {
            int cmp = stored.isEmpty() ? 1 : compareVersions(version, stored);
            if (cmp > 0) {
                log.info("Upgraded from " + from + " to " + version + ". Your data is kept"
                        + (snapshot != null ? "; a safety snapshot is in " + BACKUPS + "/" + snapshot.getName() : "") + ".");
            } else {
                log.warning("DOWNGRADED from " + stored + " to " + version + ". Downgrading is not officially supported: "
                        + "data created by newer features is ignored by this older version. "
                        + (snapshot != null ? "A snapshot of your data before this start is in " + BACKUPS + "/"
                        + snapshot.getName() + "." : ""));
            }
        }
        try {
            write(stamp, version, Math.max(storedFormat, supportedFormat));
        } catch (IOException e) {
            log.warning("Could not write " + FILE + ": " + e.getMessage());
        }
        return new Outcome(true, stored, storedFormat, snapshot);
    }

    /** Compares dotted version numbers: positive when a is newer than b. Non-numeric parts count as 0. */
    public static int compareVersions(String a, String b) {
        String[] x = a.split("[.\\-+]"), y = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** True when the folder holds saved data (anything besides the config, backups and our own stamp). */
    private static boolean hasData(File dataFolder) {
        File[] files = dataFolder.listFiles(File::isFile);
        if (files == null) return false;
        for (File f : files) if (isData(f)) return true;
        return false;
    }

    private static boolean isData(File f) {
        String n = f.getName();
        return !n.equals("config.yml") && !n.equals(FILE) && !n.endsWith(".tmp");
    }

    private static File snapshot(File dataFolder, String from, String to) throws IOException {
        File root = new File(dataFolder, BACKUPS);
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File dir = new File(root, ts + "_" + clean(from) + "-to-" + clean(to));
        Files.createDirectories(dir.toPath());
        File[] files = dataFolder.listFiles(File::isFile);
        if (files != null) {
            for (File f : files) {
                if (!isData(f) && !f.getName().equals("config.yml")) continue;   // the config is included on purpose
                Files.copy(f.toPath(), new File(dir, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        File[] all = root.listFiles(File::isDirectory);
        if (all != null && all.length > KEEP) {
            Arrays.sort(all, (a, b) -> a.getName().compareTo(b.getName()));   // names start with the timestamp
            for (int i = 0; i < all.length - KEEP; i++) deleteTree(all[i].toPath());
        }
        return dir;
    }

    private static String clean(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteTree(Path p) {
        try (var walk = Files.walk(p)) {
            List<Path> list = new ArrayList<>();
            walk.forEach(list::add);
            for (int i = list.size() - 1; i >= 0; i--) Files.deleteIfExists(list.get(i));
        } catch (IOException ignored) {
        }
    }

    private static void write(File stamp, String version, int format) throws IOException {
        stamp.getParentFile().mkdirs();
        Properties p = new Properties();
        p.setProperty("plugin-version", version);
        p.setProperty("data-format", String.valueOf(format));
        p.setProperty("updated", new Date().toString());
        File tmp = new File(stamp.getParentFile(), FILE + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp.toPath())) {
            p.store(out, "Managed by the plugin. Do not edit. Records which version last used this data.");
        }
        Files.move(tmp.toPath(), stamp.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
