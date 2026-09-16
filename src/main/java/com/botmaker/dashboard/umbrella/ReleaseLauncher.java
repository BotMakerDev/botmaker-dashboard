package com.botmaker.dashboard.umbrella;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

/**
 * Starts {@link ReleaseJob} as a process of its own, and finds it again afterwards.
 *
 * <p><b>Everything a running release is, is two files</b> under {@code releases/.running/} — gitignored in the
 * umbrella, so the pointer commit's {@code git add releases} never picks them up:
 * {@code <stamp>.out}, the child's stamped stdout and stderr, and {@code <stamp>.pid}. The window keeps no
 * state about a job, which is what lets it be closed mid-release and reattach when it is opened again: the
 * newest {@code .pid} whose process is alive is the release in progress.
 *
 * <p><b>{@code setsid} on Linux.</b> A child shares its parent's session and process group, so a terminal
 * hang-up or an IDE's stop button — which signals the whole group — would end the release along with the
 * window. {@code setsid} puts the child in a session of its own. Where there is no {@code setsid} the child is
 * started directly, and the window says which.
 *
 * <p><b>The classpath is this JVM's own</b>, module path folded in: under {@code javafx:run} the JavaFX jars
 * are on the module path and everything else is on the class path, and the child needs no JavaFX at all —
 * {@code com.botmaker.cli.release} and this package are plain jars and directories.
 */
public final class ReleaseLauncher {

    static final String JOB_CLASS = "com.botmaker.dashboard.umbrella.ReleaseJob";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    private ReleaseLauncher() {
    }

    /**
     * One job's two files.
     *
     * @param stamp when it was started, {@code yyyy-MM-dd-HHmmss} — also its name
     */
    public record Job(Path umbrella, String stamp) {

        public Path out() {
            return running(umbrella).resolve(stamp + ".out");
        }

        public Path pidFile() {
            return ReleaseLauncher.pidFile(umbrella, stamp);
        }

        public LocalDateTime startedAt() {
            return LocalDateTime.parse(stamp, STAMP);
        }

        public OptionalLong pid() {
            try {
                return OptionalLong.of(Long.parseLong(Files.readString(pidFile()).strip()));
            } catch (IOException | NumberFormatException e) {
                return OptionalLong.empty();
            }
        }

        /**
         * Whether the process is still running.
         *
         * <p>A pid is only a number and the kernel reuses it, so a live process is also asked what it is running
         * when the platform says; a pid now held by something that is not a {@code ReleaseJob} is a finished job.
         */
        public boolean alive() {
            OptionalLong pid = pid();
            if (pid.isEmpty()) {
                return false;
            }
            return ProcessHandle.of(pid.getAsLong())
                    .filter(ProcessHandle::isAlive)
                    .map(handle -> handle.info().commandLine().map(line -> line.contains(JOB_CLASS)).orElse(true))
                    .orElse(false);
        }

        /** The child's output so far. Empty when it has written nothing yet. */
        public String output() {
            try {
                return Files.readString(out(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }

        /** The model of it right now: the output, and the release log the output names once it exists. */
        public ReleaseProgress progress(Instant now) {
            List<ReleaseProgress.Line> lines = ReleaseProgress.Line.parseAll(output());
            Optional<ReleaseLog> log = ReleaseProgress.logName(lines)
                    .map(name -> umbrella.resolve("releases").resolve(name))
                    .filter(Files::isRegularFile)
                    .flatMap(file -> {
                        try {
                            return Optional.of(ReleaseLog.read(file));
                        } catch (RuntimeException e) {
                            // Caught mid-rewrite: the next read, a second later, sees the whole file.
                            return Optional.empty();
                        }
                    });
            return ReleaseProgress.of(lines, log, alive(), now);
        }
    }

    /** What {@link #launch} did, in words the tab can show. */
    public record Launched(Job job, String how) {
    }

    static Path running(Path umbrella) {
        return umbrella.resolve("releases").resolve(".running");
    }

    static Path pidFile(Path umbrella, String stamp) {
        return running(umbrella).resolve(stamp + ".pid");
    }

    /**
     * The command line, without starting anything — the part worth a test.
     *
     * @param setsid    whether to wrap it in {@code setsid}
     * @param classPath the child's class path, already joined
     */
    public static List<String> argv(boolean setsid, Path javaHome, String classPath, Path umbrella,
                                    String stamp, ReleaseSpec spec) {
        List<String> argv = new ArrayList<>();
        if (setsid) {
            argv.add("setsid");
        }
        argv.add(javaHome.resolve("bin").resolve("java").toString());
        argv.add("-cp");
        argv.add(classPath);
        argv.add(JOB_CLASS);
        argv.add(umbrella.toString());
        argv.add(stamp);
        List<String> command = spec.command(false);
        argv.addAll(command.subList(2, command.size()));
        return List.copyOf(argv);
    }

    /** {@code java.class.path} with {@code jdk.module.path} appended, skipping whichever is blank. */
    public static String classPath(String classPath, String modulePath) {
        return Stream.of(classPath, modulePath)
                .filter(part -> part != null && !part.isBlank())
                .reduce((a, b) -> a + File.pathSeparator + b)
                .orElse("");
    }

    /**
     * Starts the release. Returns once the process exists; the release itself takes minutes.
     *
     * @throws IOException when the files cannot be created or the process cannot start — nothing was run
     */
    public static Launched launch(Path umbrella, ReleaseSpec spec) throws IOException {
        String stamp = STAMP.format(LocalDateTime.now());
        Job job = new Job(umbrella, stamp);
        Files.createDirectories(running(umbrella));

        boolean setsid = System.getProperty("os.name", "").toLowerCase().contains("linux") && onPath("setsid");
        String classPath = classPath(System.getProperty("java.class.path"), System.getProperty("jdk.module.path"));
        List<String> argv = argv(setsid, Path.of(System.getProperty("java.home")), classPath, umbrella, stamp, spec);

        ProcessBuilder builder = new ProcessBuilder(argv)
                .directory(umbrella.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(job.out().toFile()))
                .redirectInput(ProcessBuilder.Redirect.from(new File(isWindows() ? "NUL" : "/dev/null")));
        Process process = builder.start();
        // The child rewrites this with its own pid as its first act; writing it here as well closes the
        // second in which a reopened window would find no job at all.
        Files.writeString(job.pidFile(), Long.toString(process.pid()));
        return new Launched(job, setsid
                ? "in its own session (setsid), process " + process.pid()
                : "as a plain child process " + process.pid() + " — no setsid here, so stopping the window's "
                        + "process group would stop it too");
    }

    /** The newest job in this checkout, finished or not. */
    public static Optional<Job> latest(Path umbrella) {
        Path dir = running(umbrella);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".pid"))
                    .map(name -> name.substring(0, name.length() - ".pid".length()))
                    .filter(ReleaseLauncher::isStamp)
                    .max(Comparator.naturalOrder())
                    .map(stamp -> new Job(umbrella, stamp));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean isStamp(String name) {
        try {
            LocalDateTime.parse(name, STAMP);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean onPath(String program) {
        String path = System.getenv("PATH");
        return path != null && Stream.of(path.split(File.pathSeparator))
                .anyMatch(dir -> Files.isExecutable(Path.of(dir, program)));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
