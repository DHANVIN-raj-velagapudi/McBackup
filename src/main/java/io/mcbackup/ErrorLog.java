package io.mcbackup;


import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The error log of this plugin. When something goes wrong it writes a readable entry to {@code logs/errors.log} in the
 * plugin folder AND a short line to the console, so a problem can always be sent for diagnosis as one small file.
 *
 * Two ways entries get in:
 *  1. On purpose: {@link #error}, {@link #warn} and {@link #info} (what was being done, who, the exception).
 *  2. Automatically: a log handler copies every WARNING/SEVERE that the plugin logger prints, and every error the
 *     server prints whose stack trace goes through this plugin's code (event handlers, scheduled tasks), into the
 *     file. So even errors nobody wrapped are not lost.
 *
 * Writing happens on its own thread (never blocks the game), the same problem is written at most 5 times a minute
 * (the rest is counted), and the file is rotated at 2 MB (3 old files are kept).
 */
public final class ErrorLog {
    public enum Kind { INFO, WARN, ERROR }

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final int KEEP = 3;
    private static final int PER_MINUTE = 5;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Logger console;
    private final File dir;
    private final File file;
    private final String pluginName;
    private final String header;
    private final String packagePrefix;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "errorlog-writer");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Window> windows = new HashMap<>();
    private final Deque<String> recent = new ArrayDeque<>();
    private final ThreadLocal<Boolean> inside = ThreadLocal.withInitial(() -> false);
    private final Handler handler = new Capture();
    private final List<Logger> attached = new ArrayList<>();
    private volatile LogRecord lastRecord;
    private int counter = (int) (System.nanoTime() & 0xFFF);

    private static final class Window {
        long start;
        int count;
        int suppressed;
    }

    /** For tests and for {@link #create}: nothing here needs a running server. */
    public ErrorLog(Logger console, File pluginFolder, String pluginName, String header, String packagePrefix) {
        this.console = console;
        this.pluginName = pluginName;
        this.header = header;
        this.packagePrefix = packagePrefix;
        this.dir = new File(pluginFolder, "logs");
        this.file = new File(dir, "errors.log");
    }


    // ---- on purpose ----------------------------------------------------------------------------

    /** Something failed. {@code where} is the feature (for example "tpa.window"), {@code ctx} are key, value pairs. */
    public String error(String where, String what, Throwable t, String... ctx) {
        return record(Kind.ERROR, where, what, t, ctx, true);
    }

    public String warn(String where, String what, String... ctx) {
        return record(Kind.WARN, where, what, null, ctx, true);
    }

    /** Something worth knowing that is not a fault (for example a window that was not opened, and why). */
    public String info(String where, String what, String... ctx) {
        return record(Kind.INFO, where, what, null, ctx, true);
    }


    // ---- reading it back -------------------------------------------------------------------------

    public File file() {
        return file;
    }

    /** The last {@code n} entries as one line each, newest last. */
    public List<String> recent(int n) {
        synchronized (recent) {
            List<String> all = new ArrayList<>(recent);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }

    // ---- life cycle -------------------------------------------------------------------------------

    /** Starts capturing the plugin's own warnings and errors, and the server's errors that come from this plugin. */
    public void start() {
        attach(console);
    }


    void attach(Logger l) {
        if (l == null || attached.contains(l)) return;
        l.addHandler(handler);
        attached.add(l);
    }

    public void stop() {
        for (Logger l : attached) l.removeHandler(handler);
        attached.clear();
        synchronized (windows) {
            flushSuppressed();
        }
        io.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- core ---------------------------------------------------------------------------------------

    private String record(Kind kind, String where, String what, Throwable t, String[] ctx, boolean toConsole) {
        String id = nextId(kind);
        String context = context(ctx);
        String top = t == null ? "" : topFrame(t);
        String key = where + "|" + what + "|" + (t == null ? "" : t.getClass().getName() + "@" + top);
        boolean write = allow(key);
        String summary = stamp() + " " + id + " " + kind + " " + where + ": " + what
                + (t == null ? "" : " [" + brief(t) + "]");
        synchronized (recent) {
            recent.addLast(summary);
            while (recent.size() > 50) recent.removeFirst();
        }
        if (toConsole && write) {
            StringBuilder sb = new StringBuilder("[").append(where).append("] ").append(what);
            if (!context.isEmpty()) sb.append(" (").append(context).append(')');
            if (t != null) {
                sb.append(" | ").append(brief(t));
                if (!top.isEmpty()) sb.append(" at ").append(top);
            }
            if (kind != Kind.INFO) sb.append(" | logged as ").append(id).append(" in logs/errors.log");
            say(kind == Kind.ERROR ? Level.SEVERE : kind == Kind.WARN ? Level.WARNING : Level.INFO, sb.toString());
        }
        if (write) {
            String entry = format(id, kind, where, what, context, t);
            try {
                io.execute(() -> append(entry));
            } catch (RuntimeException closed) {
                append(entry);   // after stop(): write it directly
            }
        }
        return id;
    }

    private void say(Level level, String text) {
        inside.set(true);
        try {
            console.log(level, text);
        } finally {
            inside.set(false);
        }
    }

    /** At most PER_MINUTE writes a minute for the same problem; the rest are counted. */
    private boolean allow(String key) {
        long now = System.currentTimeMillis();
        synchronized (windows) {
            if (windows.size() > 500) flushSuppressed();
            Window w = windows.computeIfAbsent(key, k -> new Window());
            if (now - w.start > 60_000) {
                if (w.suppressed > 0) {
                    String note = stamp() + " (" + w.suppressed + " more of: " + key + " in the last minute)\n";
                    try {
                        io.execute(() -> append(note));
                    } catch (RuntimeException closed) {
                        append(note);
                    }
                }
                w.start = now;
                w.count = 0;
                w.suppressed = 0;
            }
            w.count++;
            if (w.count > PER_MINUTE) {
                w.suppressed++;
                return false;
            }
            return true;
        }
    }

    /** Writes the "n more of ..." notes for everything still being suppressed, and forgets all windows. Lock held. */
    private void flushSuppressed() {
        for (Map.Entry<String, Window> e : windows.entrySet()) {
            if (e.getValue().suppressed > 0) {
                String note = stamp() + " (" + e.getValue().suppressed + " more of: " + e.getKey() + ")\n";
                try {
                    io.execute(() -> append(note));
                } catch (RuntimeException closed) {
                    append(note);
                }
            }
        }
        windows.clear();
    }

    private String nextId(Kind kind) {
        int n;
        synchronized (this) {
            n = ++counter;
        }
        return (kind == Kind.ERROR ? "E-" : kind == Kind.WARN ? "W-" : "I-")
                + String.format(Locale.ROOT, "%03X", n & 0xFFF);
    }

    private static String stamp() {
        return LocalDateTime.now().format(TIME);
    }

    private static String context(String[] ctx) {
        if (ctx == null || ctx.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < ctx.length; i += 2) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(ctx[i]).append('=').append(String.valueOf(ctx[i + 1]).replace('\n', ' ').replace('\r', ' '));
        }
        return sb.toString();
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 160 ? m.substring(0, 160) + "..." : m));
    }

    /** First stack line that is in this plugin's code, like "TpaManager.showWindow(TpaManager.java:84)". */
    private String topFrame(Throwable t) {
        int depth = 0;
        for (Throwable c = t; c != null && depth < 8; c = c.getCause() == c ? null : c.getCause(), depth++) {
            for (StackTraceElement e : c.getStackTrace()) {
                if (e.getClassName().startsWith(packagePrefix)) {
                    String cls = e.getClassName();
                    return cls.substring(cls.lastIndexOf('.') + 1) + "." + e.getMethodName() + "(" + e.getFileName()
                            + ":" + e.getLineNumber() + ")";
                }
            }
        }
        return "";
    }

    private boolean fromUs(Throwable t) {
        return !topFrame(t).isEmpty();
    }

    private String format(String id, Kind kind, String where, String what, String context, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(stamp()).append(" | ").append(id).append(" | ").append(kind).append(" | ").append(where)
                .append(" ===\n");
        sb.append("Running: ").append(header).append('\n');
        sb.append("What:    ").append(what).append('\n');
        if (!context.isEmpty()) sb.append("Context: ").append(context).append('\n');
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append("Error:   ").append(sw.toString().stripTrailing()).append('\n');
        }
        return sb.append('\n').toString();
    }

    private void append(String text) {
        try {
            Files.createDirectories(dir.toPath());
            if (file.exists() && file.length() > MAX_BYTES) rotate();
            Files.writeString(file.toPath(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // The error log itself must never cause errors: say so on stderr and carry on.
            System.err.println("[" + pluginName + "] could not write " + file + ": " + e);
        }
    }

    private void rotate() throws IOException {
        File last = new File(dir, "errors.log." + KEEP);
        if (last.exists()) Files.delete(last.toPath());
        for (int i = KEEP - 1; i >= 1; i--) {
            File from = new File(dir, "errors.log." + i);
            if (from.exists()) Files.move(from.toPath(), new File(dir, "errors.log." + (i + 1)).toPath());
        }
        Files.move(file.toPath(), new File(dir, "errors.log.1").toPath());
    }

    /** Copies this plugin's warnings and errors (from the plugin logger or the server logger) into the file. */
    private final class Capture extends Handler {
        @Override
        public void publish(LogRecord r) {
            if (r == null || inside.get() || r == lastRecord) return;
            if (r.getLevel().intValue() < Level.WARNING.intValue()) return;
            Throwable t = r.getThrown();
            boolean ours = console.getName().equals(r.getLoggerName()) || (t != null && fromUs(t));
            if (!ours) return;
            lastRecord = r;
            try {
                String text = r.getMessage() == null ? "" : r.getMessage();
                if (r.getParameters() != null && r.getParameters().length > 0) {
                    text = java.text.MessageFormat.format(text, r.getParameters());
                }
                Kind kind = r.getLevel().intValue() >= Level.SEVERE.intValue() ? Kind.ERROR : Kind.WARN;
                // The server already printed it (with its own stack trace); only add it to the file.
                record(kind, t == null ? "plugin-log" : "uncaught", text, t, new String[0], false);
            } catch (RuntimeException ignored) {
                // never throw out of a log handler
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
