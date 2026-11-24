package io.mcbackup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** A snapshot = a gzip'd text list of (path, size, mtime, sha256 of content). Uploading it is the commit step. */
public final class Manifest {
    public record Entry(String path, long size, long mtime, String sha) {}

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final String MAGIC = "#mcbackup-manifest v1";

    public final Tier tier;
    public final Instant created;
    public final List<Entry> entries;

    public Manifest(Tier tier, Instant created, List<Entry> entries) {
        this.tier = tier;
        this.created = created;
        this.entries = entries;
    }

    static String escape(String p) {
        StringBuilder sb = new StringBuilder();
        for (char c : p.toCharArray()) {
            switch (c) {
                case '%' -> sb.append("%25");
                case '\t' -> sb.append("%09");
                case '\n' -> sb.append("%0A");
                case '\r' -> sb.append("%0D");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescape(String p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '%' && i + 2 < p.length()) {
                String hex = p.substring(i + 1, i + 3);
                switch (hex) {
                    case "25" -> { sb.append('%'); i += 2; continue; }
                    case "09" -> { sb.append('\t'); i += 2; continue; }
                    case "0A" -> { sb.append('\n'); i += 2; continue; }
                    case "0D" -> { sb.append('\r'); i += 2; continue; }
                    default -> { }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public byte[] toGzipBytes() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(bos), StandardCharsets.UTF_8)) {
            w.write(MAGIC + "\n");
            w.write("#tier\t" + tier.id + "\n");
            w.write("#created\t" + created.toString() + "\n");
            for (Entry e : entries) {
                w.write(escape(e.path) + "\t" + e.size + "\t" + e.mtime + "\t" + e.sha + "\n");
            }
            w.write("#end\t" + entries.size() + "\n");
        }
        return bos.toByteArray();
    }

    public static Manifest parse(byte[] gz) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(gz)), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            if (!MAGIC.equals(line)) throw new IOException("Not a McBackup manifest");
            Tier tier = null;
            Instant created = null;
            List<Entry> entries = new ArrayList<>();
            boolean ended = false;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#tier\t")) {
                    tier = Tier.parse(line.substring(6));
                } else if (line.startsWith("#created\t")) {
                    created = Instant.parse(line.substring(9));
                } else if (line.startsWith("#end\t")) {
                    int n = Integer.parseInt(line.substring(5));
                    if (n != entries.size()) throw new IOException("Manifest truncated or corrupt");
                    ended = true;
                } else if (line.startsWith("#")) {
                    // unknown header: ignore
                } else {
                    String[] f = line.split("\t", -1);
                    if (f.length != 4 || !SHA.matcher(f[3]).matches()) throw new IOException("Corrupt manifest line");
                    entries.add(new Entry(unescape(f[0]), Long.parseLong(f[1]), Long.parseLong(f[2]), f[3]));
                }
            }
            if (!ended || tier == null || created == null) throw new IOException("Manifest incomplete");
            return new Manifest(tier, created, entries);
        } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
            throw new IOException("Corrupt manifest: " + e.getMessage(), e);
        }
    }
}
