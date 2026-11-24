package io.mcbackup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** In-memory S3 look-alike that VERIFIES the SigV4 signature and the payload hash of every request. */
final class MockS3 {
    static final String ACCESS = "AKTESTACCESS";
    static final String SECRET = "test/secret+KEY123";
    static final String BUCKET = "bkt";
    static final String REGION = "auto";

    static final class Fail {
        final Predicate<String> when; final int status; final AtomicInteger remaining;
        Fail(Predicate<String> when, int status, int times) { this.when = when; this.status = status; this.remaining = new AtomicInteger(times); }
    }

    final HttpServer server;
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final List<Fail> failures = new CopyOnWriteArrayList<>();
    final AtomicInteger badSignatures = new AtomicInteger();
    final AtomicInteger badPayloadHashes = new AtomicInteger();
    final AtomicInteger puts = new AtomicInteger();
    final AtomicInteger injectedFailures = new AtomicInteger();
    volatile int pageSize = 1000;

    MockS3(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    void stop() { server.stop(0); }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            byte[] body = ex.getRequestBody().readAllBytes();
            String rawPath = ex.getRequestURI().getRawPath();
            String rawQuery = ex.getRequestURI().getRawQuery();

            // --- verify payload hash + signature
            String payload = ex.getRequestHeaders().getFirst("x-amz-content-sha256");
            String amzDate = ex.getRequestHeaders().getFirst("x-amz-date");
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            boolean unsignedOk = Boolean.getBoolean("mock.allowUnsigned") && "UNSIGNED-PAYLOAD".equals(payload);
            if (method.equals("PUT") && !unsignedOk && !S3Client.sha256Hex(body).equals(payload)) {
                badPayloadHashes.incrementAndGet();
                send(ex, 400, "XAmzContentSHA256Mismatch"); return;
            }
            TreeMap<String, String> q = new TreeMap<>();
            if (rawQuery != null && !rawQuery.isEmpty()) {
                for (String kv : rawQuery.split("&")) {
                    int i = kv.indexOf('=');
                    q.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
                }
            }
            TreeMap<String, String> h = new TreeMap<>();
            h.put("host", ex.getRequestHeaders().getFirst("Host"));
            h.put("x-amz-content-sha256", payload);
            h.put("x-amz-date", amzDate);
            Instant t = LocalDateTime.parse(amzDate, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")).toInstant(ZoneOffset.UTC);
            String expected = S3Client.authorization(method, rawPath, q, h, payload, ACCESS, SECRET, REGION, "s3", t);
            if (auth == null || !auth.equals(expected)) {
                badSignatures.incrementAndGet();
                send(ex, 403, "SignatureDoesNotMatch"); return;
            }

            String path = rawPath.startsWith("/" + BUCKET) ? rawPath.substring(("/" + BUCKET).length()) : null;
            if (path == null) { send(ex, 404, "NoSuchBucket"); return; }
            String key = path.startsWith("/") ? URLDecoder.decode(path.substring(1), StandardCharsets.UTF_8) : "";

            for (Fail f : failures) {
                if (f.when.test(method + " " + key) && f.remaining.getAndDecrement() > 0) {
                    injectedFailures.incrementAndGet();
                    send(ex, f.status, "InjectedFailure"); return;
                }
            }

            switch (method) {
                case "PUT" -> { puts.incrementAndGet(); objects.put(key, body); send(ex, 200, ""); }
                case "DELETE" -> { objects.remove(key); ex.sendResponseHeaders(204, -1); }
                case "GET" -> {
                    if (key.isEmpty()) { list(ex, q); return; }
                    byte[] b = objects.get(key);
                    if (b == null) send(ex, 404, "<Error><Code>NoSuchKey</Code></Error>");
                    else { ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); }
                }
                default -> send(ex, 405, "");
            }
        } catch (Throwable t) {
            t.printStackTrace();
            try { send(ex, 500, "mock error " + t); } catch (IOException ignored) { }
        }
    }

    private void list(HttpExchange ex, TreeMap<String, String> q) throws IOException {
        String prefix = q.getOrDefault("prefix", "");
        String marker = q.get("marker");
        int max = Math.min(Integer.parseInt(q.getOrDefault("max-keys", "1000")), pageSize);
        List<String> keys = new ArrayList<>();
        for (String k : new java.util.TreeSet<>(objects.keySet())) {
            if (k.startsWith(prefix) && (marker == null || k.compareTo(marker) > 0)) keys.add(k);
        }
        boolean truncated = keys.size() > max;
        if (truncated) keys = keys.subList(0, max);
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
        sb.append("<Name>").append(BUCKET).append("</Name><IsTruncated>").append(truncated).append("</IsTruncated>");
        for (String k : keys) sb.append("<Contents><Key>").append(k).append("</Key></Contents>");
        sb.append("</ListBucketResult>");
        send(ex, 200, sb.toString());
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }

    public static void main(String[] args) throws Exception {
        MockS3 m = new MockS3(Integer.parseInt(args[0]));
        System.out.println("mock listening " + m.endpoint());
        Thread.currentThread().join();
    }
}
