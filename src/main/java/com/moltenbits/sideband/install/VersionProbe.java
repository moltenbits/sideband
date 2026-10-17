package com.moltenbits.sideband.install;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Asks an executable a hook names for its version, with {@code --version} as its only
 * argument, never through a shell. Its output goes to a file, so neither a flood of output
 * nor a child left holding a pipe can keep the probe past its timeout, and only as much of
 * that file is read back as a version line can fill. However the probe ends, the executable
 * is not left running.
 */
final class VersionProbe {

    /** {@code sideband X.Y.Z} or {@code sideband X.Y.Z-dev}, which earlier releases followed with {@code (protocol vN)}. */
    private static final Pattern VERSION = Pattern.compile("sideband (\\d+\\.\\d+\\.\\d+(?:-dev)?)(?: \\(protocol v\\d+\\))?\\n?");
    private static final int MAX_OUTPUT = 256;
    /** How long to wait for a killed probe to exit, so stopping one is bounded too. */
    private static final long STOP_WAIT_MILLIS = 1000;

    /**
     * @param version the version the target reported, or null when it gave none
     * @param failure why it gave none, or null when it did
     */
    record Result(String version, String failure) {
    }

    private final Duration timeout;

    VersionProbe(Duration timeout) {
        this.timeout = timeout;
    }

    Result probe(Path target) {
        if (!Files.exists(target)) {
            return new Result(null, "not found");
        }
        if (!Files.isRegularFile(target) || !Files.isExecutable(target)) {
            return new Result(null, "not executable");
        }
        Path output = null;
        Process process = null;
        boolean interrupted = false;
        try {
            output = Files.createTempFile("sideband-probe", ".out");
            process = new ProcessBuilder(target.toString(), "--version")
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return new Result(null, "gave no version: it timed out");
            }
            if (process.exitValue() != 0) {
                return new Result(null, "gave no version: it exited " + process.exitValue());
            }
            String answer = head(output);
            Matcher matcher = VERSION.matcher(answer);
            return answer.length() <= MAX_OUTPUT && matcher.matches()
                    ? new Result(matcher.group(1), null) : new Result(null, "gave no version: it printed no version");
        } catch (IOException e) {
            return new Result(null, "gave no version: " + e.getMessage());
        } catch (InterruptedException e) {
            interrupted = true;
            return new Result(null, "gave no version: interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                interrupted |= stop(process);
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // a temporary file left behind is harmless
                }
            }
        }
    }

    /** Kills the probe and whatever it started, and waits a bounded time for it to exit; true when that wait was interrupted. */
    private static boolean stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            return false;
        } catch (InterruptedException e) {
            return true;
        }
    }

    /** At most one byte more than a version line can be, so a longer output is seen to be longer. */
    private static String head(Path output) throws IOException {
        try (InputStream in = Files.newInputStream(output)) {
            return new String(in.readNBytes(MAX_OUTPUT + 1), UTF_8);
        }
    }
}
