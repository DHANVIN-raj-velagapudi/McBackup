package io.mcbackup;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

/**
 * Takes one incremental snapshot:
 *  1. list blobs already in the bucket
 *  2. (autosave paused) find changed files and compress them into temp files
 *  3. upload the new blobs
 *  4. upload the manifest (this is the commit)
 *  5. remember what we saw, so next time unchanged files are skipped without being read
 */
public final class SnapshotEngine {

    public interface Hooks {
        /** Called right before files are read (typically: save-all flush, save-off). */
        void beforeCopy() throws Exception;

        /** Always called afterwards, even on failure (typically: save-on). */
        void afterCopy();

        /** Called just before the copy phase starts. changedBytes = uncompressed size of files that must be uploaded. */
        default void starting(Tier tier, long changedBytes) { }

        /** Called when a scheduled backup or a background save fails, with the full exception (for the error log). */
        default void failed(String what, Throwable error) { }

        Hooks NOOP = new Hooks() {
            @Override public void beforeCopy() { }
            @Override public void afterCopy() { }
        };
    }

    public record Result(SnapshotRef ref, int files, long bytes, int uploadedBlobs, long uploadedBytes, long millis) {}

    private record FileInfo(Path path, String rel, long size, long mtime) {}

    private record RawCopy(FileInfo info, Path raw) {}

    private record Prepared(String sha, Path tmp, long compLen, String compSha) {}

    private static final class CancelledException extends IOException {
        private static final long serialVersionUID = 1L;

        CancelledException() {
            super("Backup cancelled");
        }
    }

    private final BackupConfig cfg;
    private final S3Client s3;
    private final Keys keys;
    private final Logger log;

    public SnapshotEngine(BackupConfig cfg, S3Client s3, Keys keys, Logger log) {
        this.cfg = cfg;
        this.s3 = s3;
        this.keys = keys;
        this.log = log;
    }

    public Result run(Tier tier, Hooks hooks, AtomicBoolean cancel) throws Exception {
        long t0 = System.currentTimeMillis();
        Path tmpDir = cfg.dataDir.resolve("tmp");
        Files.createDirectories(tmpDir);
        cleanDir(tmpDir);

        try {
            Set<String> remote = listRemoteBlobs();
            LocalIndex index = LocalIndex.load(cfg.dataDir.resolve("state").resolve("index.tsv"));

            // Estimate temp space needed (stat only, no pause yet)
            long estimate = 0;
            for (FileInfo fi : scan()) {
                String sha = index.trustedSha(fi.rel, fi.size, fi.mtime);
                if (sha == null || !remote.contains(sha)) estimate += fi.size;
            }
            long free = Files.getFileStore(tmpDir).getUsableSpace();
            if (estimate > 0 && free < estimate + 64L * 1024 * 1024) {
                throw new IOException("Not enough free disk space for the backup: need about "
                        + (estimate / 1_048_576) + " MB temporarily, have " + (free / 1_048_576) + " MB");
            }

            // ---- copy phase (saving paused) ----
            // Only a fast raw copy happens while autosave is off. Hashing and compressing (the slow part)
            // run afterwards from these private copies, so the world is never left unsaved for long.
            List<Manifest.Entry> entries = new ArrayList<>();
            Map<String, LocalIndex.Rec> newIndex = new HashMap<>();
            Map<String, Prepared> prepared = new HashMap<>();
            List<RawCopy> raws = new ArrayList<>();
            long scanStart;
            boolean paused = false;
            try {
                try {
                    hooks.starting(tier, estimate);
                } catch (RuntimeException ignored) {
                    // a notification problem must never stop a backup
                }
                if (cfg.pauseSaving) {
                    hooks.beforeCopy();
                    paused = true;
                }
                checkCancel(cancel);
                scanStart = System.currentTimeMillis();
                int n = 0;
                for (FileInfo fi : scan()) {
                    checkCancel(cancel);
                    String sha = index.trustedSha(fi.rel, fi.size, fi.mtime);
                    if (sha != null && remote.contains(sha)) {
                        entries.add(new Manifest.Entry(fi.rel, fi.size, fi.mtime, sha));
                        newIndex.put(fi.rel, new LocalIndex.Rec(fi.size, fi.mtime, sha));
                        continue;
                    }
                    Path raw = tmpDir.resolve("raw" + (n++) + ".bin");
                    try {
                        Files.copy(fi.path, raw, StandardCopyOption.REPLACE_EXISTING);
                    } catch (NoSuchFileException gone) {
                        continue; // deleted while we were scanning
                    }
                    raws.add(new RawCopy(fi, raw));
                }
            } finally {
                if (paused) hooks.afterCopy();
            }

            // ---- compress phase (saving is back on) ----
            Throttle throttle = new Throttle(cfg.cpuLimitPercent);
            for (RawCopy rc : raws) {
                checkCancel(cancel);
                CompressResult p = compress(rc.raw(), levelFor(rc.info().rel()), tmpDir, throttle);
                Files.deleteIfExists(rc.raw());
                String sha = p.sha;
                if (remote.contains(sha) || prepared.containsKey(sha)) {
                    Files.deleteIfExists(p.prepared.tmp()); // identical content already stored
                } else {
                    prepared.put(sha, p.prepared);
                }
                entries.add(new Manifest.Entry(rc.info().rel, p.rawLen(), rc.info().mtime, sha));
                newIndex.put(rc.info().rel, new LocalIndex.Rec(p.rawLen(), rc.info().mtime, sha));
            }

            // ---- upload phase (saving is back on) ----
            long uploadedBytes = 0;
            ExecutorService pool = Executors.newFixedThreadPool(cfg.uploadThreads, r -> {
                Thread th = new Thread(r, "McBackup-Upload");
                th.setDaemon(true);
                th.setPriority(Thread.MIN_PRIORITY);
                return th;
            });
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (Prepared p : prepared.values()) {
                    uploadedBytes += p.compLen;
                    futures.add(pool.submit(() -> {
                        if (cancel.get()) throw new CancelledException();
                        s3.putFile(keys.blob(p.sha), p.tmp, p.compSha, p.compLen);
                        Files.deleteIfExists(p.tmp);
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (ExecutionException e) {
                        pool.shutdownNow();
                        Throwable c = e.getCause();
                        if (c instanceof Exception ex) throw ex;
                        throw e;
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            checkCancel(cancel);

            // ---- commit: the manifest makes the snapshot real ----
            entries.sort(Comparator.comparing(Manifest.Entry::path));
            Instant created = Instant.now();
            SnapshotRef ref = new SnapshotRef(tier, created);
            Manifest m = new Manifest(tier, ref.ts(), entries);
            s3.putBytes(keys.manifest(ref), m.toGzipBytes());

            LocalIndex.save(cfg.dataDir.resolve("state").resolve("index.tsv"), newIndex, scanStart);

            long total = 0;
            for (Manifest.Entry e : entries) total += e.size();
            return new Result(ref, entries.size(), total, prepared.size(), uploadedBytes, System.currentTimeMillis() - t0);
        } finally {
            cleanDir(tmpDir);
        }
    }

    // ---------------------------------------------------------------------- helpers

    private static void checkCancel(AtomicBoolean cancel) throws CancelledException {
        if (cancel.get()) throw new CancelledException();
    }

    private Set<String> listRemoteBlobs() throws IOException {
        Set<String> out = new HashSet<>();
        for (String k : s3.list(keys.blobPrefix())) keys.parseBlobKey(k).ifPresent(out::add);
        return out;
    }

    private static final class CompressResult {
        final Prepared prepared;
        final String sha;
        final long rawLen;

        CompressResult(Prepared prepared, String sha, long rawLen) {
            this.prepared = prepared;
            this.sha = sha;
            this.rawLen = rawLen;
        }

        long rawLen() {
            return rawLen;
        }
    }

    /**
     * World region files, jars and images are already compressed inside, so the normal level spends CPU for almost
     * no gain. They use the cheap level instead. Restoring does not care: any gzip level reads the same.
     */
    int levelFor(String rel) {
        int dot = rel.lastIndexOf('.');
        if (dot < 0 || dot < rel.lastIndexOf('/')) return cfg.compressionLevel;
        String ext = rel.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        for (String e : cfg.precompressedExtensions) {
            if (e.equalsIgnoreCase(ext)) return cfg.precompressedLevel;
        }
        return cfg.compressionLevel;
    }

    /**
     * Keeps the compress step to a share of one CPU core: after every slice of work it sleeps long enough that the
     * work was {@code percent} of the elapsed time. 100 means no limit. Only used after saving is back on.
     */
    static final class Throttle {
        private final int percent;
        private long mark = System.nanoTime();
        private long debt;

        Throttle(int percent) {
            this.percent = percent;
        }

        /** Call once per slice of work (for example every 256 KB). */
        void pace() throws IOException {
            if (percent >= 100) return;
            long now = System.nanoTime();
            debt += (now - mark) * (100 - percent) / percent;
            if (debt >= 10_000_000L) {                       // sleep in slices of at least 10 ms
                try {
                    Thread.sleep(debt / 1_000_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancelledException();
                }
                debt = 0;
            }
            mark = System.nanoTime();
        }

        /** Start of a new file: time spent before this point is not counted as work. */
        void restart() {
            mark = System.nanoTime();
        }
    }

    private CompressResult compress(Path source, int level, Path tmpDir, Throttle throttle) throws IOException {
        Path tmp = Files.createTempFile(tmpDir, "blob", ".gz");
        MessageDigest raw = sha256();
        MessageDigest comp = sha256();
        long rawLen = 0;
        boolean ok = false;
        try {
            throttle.restart();
            try (InputStream in = Files.newInputStream(source);
                 OutputStream fos = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 64 * 1024), comp);
                 LevelGzip gz = new LevelGzip(fos, level)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                int slice = 0;
                while ((n = in.read(buf)) != -1) {
                    raw.update(buf, 0, n);
                    gz.write(buf, 0, n);
                    rawLen += n;
                    if (++slice == 4) {                      // every 256 KB
                        slice = 0;
                        throttle.pace();
                    }
                }
            }
            String sha = HexFormat.of().formatHex(raw.digest());
            Prepared p = new Prepared(sha, tmp, Files.size(tmp), HexFormat.of().formatHex(comp.digest()));
            ok = true;
            return new CompressResult(p, sha, rawLen);
        } finally {
            if (!ok) Files.deleteIfExists(tmp);
        }
    }

    private static final class LevelGzip extends GZIPOutputStream {
        LevelGzip(OutputStream out, int level) throws IOException {
            super(out, 64 * 1024);
            def.setLevel(level);
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void cleanDir(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    private List<FileInfo> scan() throws IOException {
        List<FileInfo> out = new ArrayList<>();
        Path root = cfg.serverDir.toAbsolutePath().normalize();
        Path data = cfg.dataDir.toAbsolutePath().normalize();
        List<PathMatcher> excludes = new ArrayList<>();
        for (String g : cfg.exclude) excludes.add(FileSystems.getDefault().getPathMatcher("glob:" + g));

        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                if (dir.equals(data)) return FileVisitResult.SKIP_SUBTREE; // never back up our own state/credentials
                Path probe = root.relativize(dir).resolve("_");
                for (PathMatcher m : excludes) if (m.matches(probe)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE; // skips symlinks and specials
                Path relPath = root.relativize(file);
                for (PathMatcher m : excludes) if (m.matches(relPath)) return FileVisitResult.CONTINUE;
                String rel = relPath.toString().replace(java.io.File.separatorChar, '/');
                out.add(new FileInfo(file, rel, attrs.size(), attrs.lastModifiedTime().toMillis()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                if (!(exc instanceof NoSuchFileException)) log.warning("Skipping unreadable path " + file + ": " + exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(Comparator.comparing(FileInfo::rel));
        return out;
    }
}
