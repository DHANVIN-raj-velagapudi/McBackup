package io.mcbackup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Minimal S3-compatible client (AWS Signature V4, path-style URLs). Works with Google Cloud Storage
 * "interoperability" HMAC keys, Cloudflare R2, MinIO, AWS S3. Only what the backup needs:
 * put / get / list / delete.
 */
public final class S3Client {

    public static final class S3Exception extends IOException {
        private static final long serialVersionUID = 1L;
        public final int status;

        S3Exception(int status, String message) {
            super(message);
            this.status = status;
        }

        boolean retryable() {
            return status == 429 || status >= 500;
        }
    }

    @FunctionalInterface
    private interface IOCall<T> {
        T call() throws IOException;
    }

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final int MAX_ATTEMPTS = 5;
    private static final String EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final String endpoint;
    private final String region;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String hostHeader;
    private final Supplier<Instant> clock;
    private final Throttle throttle;
    long retryBaseMillis = 1000; // tests lower this

    public S3Client(String endpoint, String region, String bucket, String accessKey, String secretKey, double maxUploadMbps) {
        this(endpoint, region, bucket, accessKey, secretKey, maxUploadMbps, Instant::now);
    }

    S3Client(String endpoint, String region, String bucket, String accessKey, String secretKey,
             double maxUploadMbps, Supplier<Instant> clock) {
        String e = endpoint;
        while (e.endsWith("/")) e = e.substring(0, e.length() - 1);
        this.endpoint = e;
        this.region = region;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        URI u = URI.create(e);
        this.hostHeader = u.getPort() == -1 ? u.getHost() : u.getHost() + ":" + u.getPort();
        this.clock = clock;
        this.throttle = new Throttle(maxUploadMbps);
    }

    // ------------------------------------------------------------------ public operations

    public void putBytes(String key, byte[] data) throws IOException {
        String sha = sha256Hex(data);
        retry("PUT " + key, () -> {
            HttpURLConnection c = open("PUT", objectPath(key), new TreeMap<>(), sha);
            try {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(data);
                }
                expectOk(c, "PUT " + key);
            } finally {
                c.disconnect();
            }
            return null;
        });
    }

    /** Uploads a file. payloadSha256 must be the SHA-256 (hex) of the file's exact bytes; it is part of the signature. */
    public void putFile(String key, Path file, String payloadSha256, long length) throws IOException {
        retry("PUT " + key, () -> {
            HttpURLConnection c = open("PUT", objectPath(key), new TreeMap<>(), payloadSha256);
            try {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(length);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                try (InputStream in = Files.newInputStream(file); OutputStream out = c.getOutputStream()) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        throttle.acquire(n);
                        out.write(buf, 0, n);
                    }
                }
                expectOk(c, "PUT " + key);
            } finally {
                c.disconnect();
            }
            return null;
        });
    }

    public byte[] getBytes(String key) throws IOException {
        return retry("GET " + key, () -> {
            HttpURLConnection c = open("GET", objectPath(key), new TreeMap<>(), EMPTY_SHA);
            try {
                checkStatus(c, "GET " + key);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    in.transferTo(bos);
                    return bos.toByteArray();
                }
            } finally {
                c.disconnect();
            }
        });
    }

    /** Opens a streaming download. The caller must close the stream. */
    public InputStream getStream(String key) throws IOException {
        HttpURLConnection c = retry("GET " + key, () -> {
            HttpURLConnection conn = open("GET", objectPath(key), new TreeMap<>(), EMPTY_SHA);
            try {
                checkStatus(conn, "GET " + key);
            } catch (IOException e) {
                conn.disconnect();
                throw e;
            }
            return conn;
        });
        InputStream in = c.getInputStream();
        return new java.io.FilterInputStream(in) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    c.disconnect();
                }
            }
        };
    }

    public void delete(String key) throws IOException {
        retry("DELETE " + key, () -> {
            HttpURLConnection c = open("DELETE", objectPath(key), new TreeMap<>(), EMPTY_SHA);
            try {
                int code = c.getResponseCode();
                if (code == 404) return null; // already gone
                if (code / 100 != 2) throw error(c, code, "DELETE " + key);
                drain(c);
            } finally {
                c.disconnect();
            }
            return null;
        });
    }

    /** Lists all object keys that start with prefix (follows pagination). */
    public List<String> list(String prefix) throws IOException {
        return new ArrayList<>(listSizes(prefix).keySet());
    }

    /** Like list, but also returns each object's size in bytes (in listing order). */
    public Map<String, Long> listSizes(String prefix) throws IOException {
        Map<String, Long> all = new LinkedHashMap<>();
        String marker = null;
        while (true) {
            final String m = marker;
            ListPage page = retry("LIST " + prefix, () -> {
                TreeMap<String, String> q = new TreeMap<>();
                q.put("prefix", prefix);
                q.put("max-keys", "1000");
                if (m != null) q.put("marker", m);
                HttpURLConnection c = open("GET", "/" + enc(bucket, false), q, EMPTY_SHA);
                try {
                    checkStatus(c, "LIST " + prefix);
                    try (InputStream in = c.getInputStream()) {
                        return parseList(in);
                    }
                } finally {
                    c.disconnect();
                }
            });
            for (int i = 0; i < page.keys.size(); i++) all.put(page.keys.get(i), page.sizes.get(i));
            if (!page.truncated || page.keys.isEmpty()) break;
            marker = page.keys.get(page.keys.size() - 1);
        }
        return all;
    }

    // ------------------------------------------------------------------ internals

    private String objectPath(String key) {
        return "/" + enc(bucket, false) + "/" + enc(key, true);
    }

    private HttpURLConnection open(String method, String canonicalPath, SortedMap<String, String> query, String payloadSha) throws IOException {
        Instant now = clock.get();
        String amzDate = AMZ_DATE.format(now);
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", hostHeader);
        headers.put("x-amz-content-sha256", payloadSha);
        headers.put("x-amz-date", amzDate);
        String auth = authorization(method, canonicalPath, query, headers, payloadSha, accessKey, secretKey, region, "s3", now);
        String qs = canonicalQuery(query);
        URI uri = URI.create(endpoint + canonicalPath + (qs.isEmpty() ? "" : "?" + qs));
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("x-amz-date", amzDate);
        c.setRequestProperty("x-amz-content-sha256", payloadSha);
        c.setRequestProperty("Authorization", auth);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(120_000);
        return c;
    }

    private void checkStatus(HttpURLConnection c, String what) throws IOException {
        int code = c.getResponseCode();
        if (code / 100 != 2) throw error(c, code, what);
    }

    private void expectOk(HttpURLConnection c, String what) throws IOException {
        checkStatus(c, what);
        drain(c);
    }

    private static void drain(HttpURLConnection c) {
        try (InputStream in = c.getInputStream()) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // response body is irrelevant on success
        }
    }

    private S3Exception error(HttpURLConnection c, int code, String what) {
        String body = "";
        try (InputStream in = c.getErrorStream()) {
            if (in != null) {
                byte[] b = in.readNBytes(600);
                body = new String(b, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
            }
        } catch (IOException ignored) {
            // keep empty
        }
        return new S3Exception(code, what + " -> HTTP " + code + (body.isEmpty() ? "" : " " + body));
    }

    private <T> T retry(String what, IOCall<T> op) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return op.call();
            } catch (S3Exception e) {
                if (!e.retryable()) throw e;
                last = e;
            } catch (IOException e) {
                last = e;
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(Math.min(30_000, retryBaseMillis << (attempt - 1)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException(what + " interrupted", ie);
                }
            }
        }
        throw new IOException(what + " failed after " + MAX_ATTEMPTS + " attempts: " + last.getMessage(), last);
    }

    private record ListPage(List<String> keys, List<Long> sizes, boolean truncated) {}

    private static ListPage parseList(InputStream in) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder().parse(in);
            List<String> keys = new ArrayList<>();
            List<Long> sizes = new ArrayList<>();
            NodeList contents = doc.getElementsByTagName("Contents");
            for (int i = 0; i < contents.getLength(); i++) {
                NodeList k = ((org.w3c.dom.Element) contents.item(i)).getElementsByTagName("Key");
                if (k.getLength() > 0) {
                    keys.add(k.item(0).getTextContent());
                    NodeList sz = ((org.w3c.dom.Element) contents.item(i)).getElementsByTagName("Size");
                    long size = 0;
                    try { if (sz.getLength() > 0) size = Long.parseLong(sz.item(0).getTextContent().trim()); } catch (NumberFormatException ignored) { }
                    sizes.add(size);
                }
            }
            NodeList tr = doc.getElementsByTagName("IsTruncated");
            boolean truncated = tr.getLength() > 0 && "true".equalsIgnoreCase(tr.item(0).getTextContent().trim());
            return new ListPage(keys, sizes, truncated);
        } catch (Exception e) {
            throw new IOException("Could not parse bucket listing: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ SigV4

    /** RFC 3986 percent-encoding as required by SigV4. */
    static String enc(String s, boolean keepSlash) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || (keepSlash && c == '/')) {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return sb.toString();
    }

    static String canonicalQuery(Map<String, String> query) {
        List<String[]> pairs = new ArrayList<>();
        for (Map.Entry<String, String> e : query.entrySet()) {
            pairs.add(new String[]{enc(e.getKey(), false), enc(e.getValue(), false)});
        }
        pairs.sort((a, b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]));
        StringBuilder sb = new StringBuilder();
        for (String[] p : pairs) {
            if (sb.length() > 0) sb.append('&');
            sb.append(p[0]).append('=').append(p[1]);
        }
        return sb.toString();
    }

    /**
     * Builds the Authorization header value. headers: lowercase names -> trimmed values; every header
     * given here is signed (must include host, x-amz-date, and x-amz-content-sha256 for S3).
     */
    static String authorization(String method, String canonicalPath, Map<String, String> query,
                                SortedMap<String, String> headers, String payloadSha, String accessKey,
                                String secretKey, String region, String service, Instant t) {
        StringBuilder canonHeaders = new StringBuilder();
        StringBuilder signed = new StringBuilder();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            canonHeaders.append(h.getKey()).append(':').append(h.getValue().trim()).append('\n');
            if (signed.length() > 0) signed.append(';');
            signed.append(h.getKey());
        }
        String canonicalRequest = method + "\n" + canonicalPath + "\n" + canonicalQuery(query) + "\n"
                + canonHeaders + "\n" + signed + "\n" + payloadSha;
        String date = DATE_ONLY.format(t);
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + AMZ_DATE.format(t) + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] k = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        k = hmac(k, region);
        k = hmac(k, service);
        k = hmac(k, "aws4_request");
        String sig = HexFormat.of().formatHex(hmac(k, stringToSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signed + ", Signature=" + sig;
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ bandwidth cap

    private static final class Throttle {
        private final double bytesPerSec;
        private long next = System.nanoTime();

        Throttle(double mbps) {
            this.bytesPerSec = mbps * 1_000_000.0 / 8.0;
        }

        synchronized void acquire(int n) throws IOException {
            if (bytesPerSec <= 0) return;
            long now = System.nanoTime();
            if (next < now) next = now;
            next += (long) (n * 1e9 / bytesPerSec);
            long sleepNs = next - now - 250_000_000L; // allow a small burst
            if (sleepNs > 0) {
                try {
                    Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("upload interrupted", e);
                }
            }
        }
    }
}
