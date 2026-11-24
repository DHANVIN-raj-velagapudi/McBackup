package io.mcbackup;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps config.yml in step with the plugin version.
 *
 * <p>config.yml stores the plugin version that last updated it (key {@code config-version}). When a newer jar is
 * installed, the options that are new in this version are added to the file (with their comments), every value the
 * admin already set is kept, a backup of the old file is saved in {@code config-backups/}, and the stored version is
 * raised. Admins should never edit {@code config-version} by hand.
 */
public final class ConfigUpdater {
    private static final String KEY = "config-version";
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^config-version:.*$");
    private static final int KEEP_BACKUPS = 10;

    private ConfigUpdater() {
    }

    /** What happened: nothing to do, a fresh install, or an update. */
    public record Result(Kind kind, String from, String to, List<String> added, File backup) {
        public enum Kind { UNCHANGED, FRESH, UPDATED, FAILED }
    }

    /**
     * @param dataFolder    the plugin's data folder
     * @param defaults      opens the config.yml shipped inside the jar (null stream = nothing to do)
     * @param version       the running plugin version
     * @param userSections  sections whose entries the admin owns (e.g. rank lists): when the section already exists,
     *                      entries the admin removed are NOT added back
     */
    public static Result update(File dataFolder, Supplier<InputStream> defaults, String version, Logger log,
                                Set<String> userSections) {
        if (dataFolder == null) return new Result(Result.Kind.UNCHANGED, "", version, List.of(), null);
        File file = new File(dataFolder, "config.yml");
        try (InputStream in = defaults.get()) {
            if (in == null) return new Result(Result.Kind.UNCHANGED, "", version, List.of(), null);
            byte[] jarBytes = in.readAllBytes();
            if (!file.exists()) {
                dataFolder.mkdirs();
                writeAtomic(file, stamp(new String(jarBytes, StandardCharsets.UTF_8), version));
                return new Result(Result.Kind.FRESH, "", version, List.of(), null);
            }

            YamlConfiguration user = new YamlConfiguration();
            try {
                user.load(file);            // throws on a typo, so a broken file is never "repaired" over the top
            } catch (InvalidConfigurationException e) {
                log.severe("config.yml has a YAML error, so it was NOT updated. Fix it (or delete it to start fresh): "
                        + e.getMessage());
                return new Result(Result.Kind.FAILED, "", version, List.of(), null);
            }
            String stored = user.getString(KEY, "");
            if (stored.equals(version)) return new Result(Result.Kind.UNCHANGED, stored, version, List.of(), null);

            YamlConfiguration def = new YamlConfiguration();
            def.load(new InputStreamReader(new java.io.ByteArrayInputStream(jarBytes), StandardCharsets.UTF_8));
            // decided BEFORE adding anything, so a section created below is not mistaken for one the admin owns
            Set<String> owned = new HashSet<>();
            for (String s : userSections) if (user.isConfigurationSection(s)) owned.add(s);

            File backup = backup(dataFolder, file, stored.isEmpty() ? "unknown" : stored);
            List<String> added = new ArrayList<>();
            for (String key : def.getKeys(true)) {
                if (key.equals(KEY) || user.contains(key)) continue;
                if (insideOwned(owned, key)) continue;
                int dot = key.lastIndexOf('.');
                if (dot > 0) {
                    String parent = key.substring(0, dot);
                    if (user.contains(parent) && !user.isConfigurationSection(parent)) continue; // admin turned a section into a value
                }
                Object v = def.get(key);
                if (v instanceof ConfigurationSection) {
                    user.createSection(key);
                } else {
                    user.set(key, v);
                    added.add(key);
                }
                user.setComments(key, def.getComments(key));
                user.setInlineComments(key, def.getInlineComments(key));
            }
            user.set(KEY, version);
            user.setComments(KEY, def.getComments(KEY));
            user.setInlineComments(KEY, def.getInlineComments(KEY));
            user.options().setHeader(def.options().getHeader());

            writeAtomic(file, stamp(user.saveToString(), version));
            String from = stored.isEmpty() ? "an older version" : stored;
            log.info("config.yml updated from " + from + " to " + version + ": " + added.size()
                    + " new option(s) added, all your own settings were kept.");
            if (!added.isEmpty()) {
                log.info("New options: " + String.join(", ", added.subList(0, Math.min(added.size(), 25)))
                        + (added.size() > 25 ? ", ..." : ""));
            }
            log.info("A backup of your old config is in " + backup.getParentFile().getName() + "/" + backup.getName()
                    + ". Please do not edit the '" + KEY + "' line: it is managed automatically.");
            return new Result(Result.Kind.UPDATED, stored, version, added, backup);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            log.severe("Could not update config.yml (your file was left as it was): " + e.getMessage());
            return new Result(Result.Kind.FAILED, "", version, List.of(), null);
        }
    }

    private static boolean insideOwned(Set<String> owned, String key) {
        for (String s : owned) if (key.startsWith(s + ".")) return true;
        return false;
    }

    /** Writes the stored version as a quoted string, whatever the YAML writer did with it. */
    static String stamp(String yaml, String version) {
        String line = KEY + ": \"" + version + "\"";
        Matcher m = VERSION_LINE.matcher(yaml);
        if (m.find()) return m.replaceFirst(Matcher.quoteReplacement(line));
        return line + "\n" + yaml;
    }

    private static File backup(File dataFolder, File file, String storedVersion) throws IOException {
        File dir = new File(dataFolder, "config-backups");
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File target = new File(dir, "config-" + storedVersion.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + stamp + ".yml");
        Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        File[] all = dir.listFiles((d, n) -> n.startsWith("config-") && n.endsWith(".yml"));
        if (all != null && all.length > KEEP_BACKUPS) {
            Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < all.length - KEEP_BACKUPS; i++) all[i].delete();
        }
        return target;
    }

    private static void writeAtomic(File file, String text) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
