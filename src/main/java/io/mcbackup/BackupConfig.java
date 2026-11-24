package io.mcbackup;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Plain settings object; filled from config.yml (plugin) or environment (CLI). */
public final class BackupConfig {
    public static final List<String> DEFAULT_EXCLUDES = List.of(
            "logs/**", "cache/**", "crash-reports/**", "libraries/**", "versions/**",
            "**/session.lock", "**/*.tmp");

    // storage (S3-compatible; Google Cloud Storage via interoperability keys)
    public String endpoint = "https://storage.googleapis.com";
    public String region = "auto";
    public String bucket = "";
    public String prefix = "minecraft";
    public String accessKey = "";
    public String secretKey = "";

    // paths
    public Path serverDir;
    public Path dataDir;
    public List<String> exclude = new ArrayList<>(DEFAULT_EXCLUDES);

    // schedule
    public ZoneId zone = ZoneId.of("Asia/Kolkata");
    public int hourlyMinute = 5;
    public LocalTime dailyTime = LocalTime.of(0, 0);
    public DayOfWeek weeklyDay = DayOfWeek.SUNDAY;
    public LocalTime weeklyTime = LocalTime.of(0, 30);

    // retention
    public int keepWeekly = 4;
    public int maxHourly = 48;   // safety caps, only matter if the daily/weekly jobs keep failing
    public int maxDaily = 14;

    // behaviour
    public boolean pauseSaving = true;
    public int compressionLevel = 3;
    public int uploadThreads = 2;
    public double maxUploadMbps = 0; // 0 = unlimited
    public int retryMinutes = 10;    // wait this long after a failed scheduled job before retrying

    public String keyRoot() {
        String p = prefix == null ? "" : prefix.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "" : p + "/";
    }

    public void validate() {
        if (bucket == null || bucket.isBlank() || bucket.equals("YOUR-BUCKET")) {
            throw new IllegalArgumentException("storage.bucket is not set");
        }
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("storage access-key / secret-key are not set");
        }
        if (endpoint == null || !endpoint.startsWith("http")) {
            throw new IllegalArgumentException("storage.endpoint must start with http(s)://");
        }
        if (serverDir == null || dataDir == null) throw new IllegalArgumentException("paths not set");
        if (keepWeekly < 1 || maxHourly < 1 || maxDaily < 1) {
            throw new IllegalArgumentException("retention values must be at least 1");
        }
        if (hourlyMinute < 0 || hourlyMinute > 59) throw new IllegalArgumentException("hourly-minute must be 0-59");
        if (compressionLevel < 1 || compressionLevel > 9) throw new IllegalArgumentException("compression-level must be 1-9");
        if (uploadThreads < 1 || uploadThreads > 8) throw new IllegalArgumentException("upload-threads must be 1-8");
    }
}
