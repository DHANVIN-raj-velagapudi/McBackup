package io.mcbackup;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Plain checks for ErrorLog (no server needed): java -cp ... io.mcbackup.ErrorLogCheck */
public final class ErrorLogCheck {
    private static int failed;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (!ok) failed++;
    }

    private static final class Lines extends Handler {
        final List<String> lines = new ArrayList<>();

        @Override
        public void publish(LogRecord r) {
            lines.add(r.getLevel() + " " + r.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static String read(File f) throws Exception {
        return f.exists() ? Files.readString(f.toPath()) : "";
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("errorlog-check").toFile();
        Logger plugin = Logger.getLogger("CheckPlugin");
        plugin.setUseParentHandlers(false);
        Lines console = new Lines();
        plugin.addHandler(console);
        ErrorLog log = new ErrorLog(plugin, dir, "CheckPlugin", "CheckPlugin 1.0 | test server | Java", "io.mcbackup.");
        log.start();

        // 1. An error written on purpose: console line + file entry with context and stack trace
        String id = log.error("tpa.window", "Request window failed to open", new IllegalStateException("boom"),
                "player", "Steve", "from", "Alex");
        check("error() returns an id", id.startsWith("E-"));
        check("console line names feature, context, exception and the file",
                console.lines.size() == 1 && console.lines.get(0).contains("[tpa.window]")
                        && console.lines.get(0).contains("player=Steve") && console.lines.get(0).contains("IllegalStateException: boom")
                        && console.lines.get(0).contains("logs/errors.log") && console.lines.get(0).contains(id));
        check("console line points at our code", console.lines.get(0).contains("ErrorLogCheck.main("));

        // 2. warn / info
        log.warn("teleport", "Teleport did not happen", "player", "Steve");
        log.info("tpa.safety", "Teleport refused", "host", "Alex");
        check("warn and info reach the console", console.lines.size() == 3);

        // 3. The capture handler: the plugin logger's own warnings (no double console line, entry in the file)
        plugin.log(Level.WARNING, "starter-kit.items: 'FOO' is not a valid item, skipped.");
        plugin.log(Level.INFO, "just an info line");
        check("plugin logger warning is captured, info is not", log.recent(10).stream()
                .anyMatch(s -> s.contains("plugin-log") && s.contains("FOO")) && log.recent(10).stream()
                .noneMatch(s -> s.contains("just an info line")));
        check("capture did not echo to the console again", console.lines.size() == 5);

        // 4. Server-style records: an error whose stack goes through our code is captured, another plugin's is not
        Logger server = Logger.getLogger("ServerCheck");
        server.setUseParentHandlers(false);
        log.attach(server);
        server.log(Level.SEVERE, "Could not pass event X to CheckPlugin", new RuntimeException("ours"));
        RuntimeException other = new RuntimeException("not ours");
        other.setStackTrace(new StackTraceElement[]{new StackTraceElement("org.someone.Else", "run", "Else.java", 1)});
        server.log(Level.SEVERE, "Could not pass event Y to OtherPlugin", other);
        check("server error from our code is captured", log.recent(10).stream().anyMatch(s -> s.contains("event X")));
        check("server error from another plugin is ignored", log.recent(10).stream().noneMatch(s -> s.contains("event Y")));

        // 5. The same problem is written at most 5 times a minute
        for (int i = 0; i < 30; i++) log.error("loop", "same thing", new RuntimeException("again"));
        // 6. Rotation: many different entries make the file grow past 2 MB
        String big = "x".repeat(900);
        for (int i = 0; i < 2600; i++) log.warn("fill-" + i, big);
        log.stop();

        String file = read(log.file());
        check("file has the readable header line", (file + read(new File(dir, "logs/errors.log.1"))).contains("| ERROR | tpa.window ==="));
        check("file has run info", read(new File(dir, "logs/errors.log.1")).contains("Running: CheckPlugin 1.0")
                || file.contains("Running: CheckPlugin 1.0"));
        check("rotation made errors.log.1", new File(dir, "logs/errors.log.1").exists());
        String all = file + read(new File(dir, "logs/errors.log.1")) + read(new File(dir, "logs/errors.log.2"));
        long loops = all.lines().filter(l -> l.contains("| loop ===")).count();
        check("rate limit: 30 identical errors wrote 5 entries", loops == 5);
        check("rate limit: the rest is counted", all.contains("more of: loop|same thing"));
        check("stack trace is in the file", all.contains("IllegalStateException: boom") && all.contains("at io.mcbackup.ErrorLogCheck.main"));
        check("context is in the file", all.contains("Context: player=Steve, from=Alex"));
        check("never more than 3 old files", !new File(dir, "logs/errors.log.4").exists());

        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        if (failed != 0) System.exit(1);
    }
}
