package io.mcbackup;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers (size, mtime, sha) from the last successful snapshot so unchanged files are never re-read.
 * Entries whose mtime is within RACY_MS of the scan time are not trusted next time (same idea as git's "racy" check).
 */
final class LocalIndex {
    static final long RACY_MS = 2000;

    record Rec(long size, long mtime, String sha) {}

    private final Map<String, Rec> map;
    private final long scanTime;

    LocalIndex(Map<String, Rec> map, long scanTime) {
        this.map = map;
        this.scanTime = scanTime;
    }

    static LocalIndex empty() {
        return new LocalIndex(new HashMap<>(), 0);
    }

    /** Returns the cached sha if the file is unchanged and the record is trustworthy, else null. */
    String trustedSha(String path, long size, long mtime) {
        Rec r = map.get(path);
        if (r == null) return null;
        if (r.size != size || r.mtime != mtime) return null;
        if (r.mtime >= scanTime - RACY_MS) return null;
        return r.sha;
    }

    static LocalIndex load(Path file) {
        Map<String, Rec> m = new HashMap<>();
        long scan = 0;
        if (!Files.isRegularFile(file)) return empty();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#scan\t")) {
                    scan = Long.parseLong(line.substring(6));
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    String[] f = line.split("\t", -1);
                    if (f.length == 4) {
                        m.put(Manifest.unescape(f[0]), new Rec(Long.parseLong(f[1]), Long.parseLong(f[2]), f[3]));
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            return empty(); // a broken cache just means everything is re-hashed
        }
        return new LocalIndex(m, scan);
    }

    static void save(Path file, Map<String, Rec> recs, long scanTime) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write("#scan\t" + scanTime + "\n");
            for (Map.Entry<String, Rec> e : recs.entrySet()) {
                Rec r = e.getValue();
                w.write(Manifest.escape(e.getKey()) + "\t" + r.size + "\t" + r.mtime + "\t" + r.sha + "\n");
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
