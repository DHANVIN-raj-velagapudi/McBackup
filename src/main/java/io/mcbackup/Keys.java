package io.mcbackup;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Object key layout inside the bucket. */
public final class Keys {
    private static final Pattern MANIFEST = Pattern.compile("snapshots/((?:hourly|daily|weekly)/\\d{8}T\\d{6}Z)\\.manifest\\.gz");
    private static final Pattern BLOB = Pattern.compile("blobs/[0-9a-f]{2}/([0-9a-f]{64})\\.gz");

    private final String root; // "" or "something/"

    public Keys(String root) {
        this.root = root;
    }

    public String blob(String sha) {
        return root + "blobs/" + sha.substring(0, 2) + "/" + sha + ".gz";
    }

    public String blobPrefix() {
        return root + "blobs/";
    }

    public String snapshotPrefix() {
        return root + "snapshots/";
    }

    public String manifest(SnapshotRef r) {
        return root + "snapshots/" + r.id() + ".manifest.gz";
    }

    public Optional<SnapshotRef> parseManifestKey(String key) {
        if (!key.startsWith(root)) return Optional.empty();
        Matcher m = MANIFEST.matcher(key.substring(root.length()));
        if (!m.matches()) return Optional.empty();
        try {
            return Optional.of(SnapshotRef.parseId(m.group(1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Optional<String> parseBlobKey(String key) {
        if (!key.startsWith(root)) return Optional.empty();
        Matcher m = BLOB.matcher(key.substring(root.length()));
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
