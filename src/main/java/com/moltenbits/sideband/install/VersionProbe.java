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
 * nor a child left holding a pipe can keep the probe past its timeout.
 */
final class VersionProbe {

    /** {@code sideband X.Y.Z} or {@code sideband X.Y.Z-dev}, which earlier releases followed with {@code (protocol vN)}. */
    private static final Pattern VERSION = Pattern.compile("sideband (\\d+\\.\\d+\\.\\d+(?:-dev)?)(?: \\(protocol v\\d+\\))?\\n?");
    private static final int MAX_OUTPUT = 256;

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
        try {
            output = Files.createTempFile("sideband-probe", ".out");
            Process process = new ProcessBuilder(target.toString(), "--version")
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return new Result(null, "gave no version: it timed out");
            }
            if (process.exitValue() != 0) {
                return new Result(null, "gave no version: it exited " + process.exitValue());
            }
            Matcher matcher = VERSION.matcher(head(output));
            return matcher.matches() ? new Result(matcher.group(1), null) : new Result(null, "gave no version: it printed no version");
        } catch (IOException e) {
            return new Result(null, "gave no version: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(null, "gave no version: interrupted");
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // a temporary file left behind is harmless
                }
            }
        }
    }

    /** At most one byte more than a version line can be, so a longer output cannot match. */
    private static String head(Path output) throws IOException {
        try (InputStream in = Files.newInputStream(output)) {
            return new String(in.readNBytes(MAX_OUTPUT + 1), UTF_8);
        }
    }
}
