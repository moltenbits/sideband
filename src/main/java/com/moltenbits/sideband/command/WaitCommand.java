package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.journal.Read;
import com.moltenbits.sideband.waiting.JournalWatcher;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;
import io.micronaut.context.annotation.Prototype;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Blocks until at least one complete entry exists past a byte offset, then prints the
 * entries and the offset to resume from. Idle waiting happens inside this process, so a
 * client blocked on it spends no model tokens.
 */
@Command(name = "wait", description = "Block until complete entries are appended after a byte offset")
@Prototype
public class WaitCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = "--repo", description = "A directory inside the repository (default: current directory)")
    Path repository = Path.of(System.getProperty("user.dir"));

    @Option(names = "--from", required = true, description = "Byte offset to watch from; entries before it are ignored")
    long from;

    @Option(names = "--timeout", description = "Seconds to wait before giving up; waits indefinitely when omitted")
    Long timeoutSeconds;

    private final SidebandHome home;
    private final JournalWatcher watcher;
    private final ObjectMapper json;

    WaitCommand(SidebandHome home, JournalWatcher watcher, ObjectMapper json) {
        this.home = home;
        this.watcher = watcher;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        if (from < 0) {
            throw new IllegalArgumentException("--from must not be negative");
        }
        if (timeoutSeconds != null && timeoutSeconds < 0) {
            throw new IllegalArgumentException("--timeout must not be negative");
        }
        Path file = home.locate(repository).resolve(Journal.FILE_NAME);
        Optional<Read> read = timeoutSeconds == null
                ? Optional.of(watcher.await(file, from))
                : watcher.await(file, from, Duration.ofSeconds(timeoutSeconds));
        Output output = read.map(Output::of).orElseGet(() -> Output.timedOut(from));
        spec.commandLine().getOut().println(json.writeValueAsString(output));
        return output.timedOut() ? ExitCode.TIMED_OUT : ExitCode.OK;
    }

    /** What a caller sees: the entries found, where to resume, and whether the wait gave up. */
    @Serdeable(naming = SnakeCaseStrategy.class)
    record Output(long start, long end, List<String> entries, boolean timedOut) {

        static Output of(Read read) {
            return new Output(read.start(), read.end(), read.entries(), false);
        }

        static Output timedOut(long offset) {
            return new Output(offset, offset, List.of(), true);
        }
    }
}
