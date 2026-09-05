package com.moltenbits.sideband.command;

import com.moltenbits.sideband.ancestry.Ancestry;
import com.moltenbits.sideband.ancestry.EntryIndex;
import com.moltenbits.sideband.ancestry.InvalidLineageException;
import com.moltenbits.sideband.ancestry.Lineage;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.journal.Diagnostic;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.journal.Read;
import com.moltenbits.sideband.protocol.DeliveryPolicy;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.recipient.Cursor;
import com.moltenbits.sideband.recipient.RecipientState;
import com.moltenbits.sideband.waiting.JournalWatcher;
import com.moltenbits.sideband.waiting.Waited;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * Blocks until complete entries exist past a byte offset, then prints them with the offset
 * to resume from. With {@code --role}, only open entries for that role are returned, each
 * with the live policy the recipient must apply. This belongs to a session's background
 * listener; idle waiting happens inside this process, so the client spends no model tokens.
 */
@Command(name = "wait", description = "Block until complete entries are appended after a byte offset")
@Prototype
public class WaitCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--from", required = true, description = "Byte offset to watch from; entries before it are ignored")
    long from;

    @Option(names = "--role", description = "Return only open entries addressed to this role: claude or codex")
    Role role;

    @Option(names = "--timeout", description = "Seconds to wait before giving up; waits indefinitely when omitted")
    Long timeoutSeconds;

    private final SidebandHome home;
    private final JournalWatcher watcher;
    private final Journal journal;
    private final RecipientState recipients;
    private final Ancestry ancestry;
    private final ObjectMapper json;

    WaitCommand(SidebandHome home, JournalWatcher watcher, Journal journal, RecipientState recipients,
                Ancestry ancestry, ObjectMapper json) {
        this.home = home;
        this.watcher = watcher;
        this.journal = journal;
        this.recipients = recipients;
        this.ancestry = ancestry;
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
        Path stateDirectory = home.locate(repository.directory);
        Path file = stateDirectory.resolve(Journal.FILE_NAME);
        Duration timeout = timeoutSeconds == null ? null : Duration.ofSeconds(timeoutSeconds);
        Cursor cursor = role == null ? null : recipients.load(stateDirectory, role);
        Predicate<Entry> filter = cursor == null ? entry -> true : entry -> recipients.isOpen(cursor, entry);
        Waited waited = watcher.await(file, from, timeout, filter);
        Read read = waited.read();
        List<Delivery> deliveries = new ArrayList<>();
        if (!read.isEmpty()) {
            EntryIndex index = index(file);
            for (Entry entry : read.entries()) {
                deliveries.add(Delivery.of(entry, ancestry, index));
            }
        }
        Output.print(spec, json, new Result(read.start(), read.end(), deliveries, read.diagnostics(), waited.timedOut()));
        return waited.timedOut() ? ExitCode.TIMED_OUT : ExitCode.OK;
    }

    private EntryIndex index(Path file) {
        Map<String, EntryMetadata> byId = new HashMap<>();
        for (Entry entry : journal.readCompleteFrom(file, 0).entries()) {
            byId.put(entry.metadata().id(), entry.metadata());
        }
        return id -> Optional.ofNullable(byId.get(id));
    }

    /** What the listener sees: the entries found, skipped regions, where to resume, and whether it gave up. */
    @Serdeable(naming = SnakeCaseStrategy.class)
    record Result(long start, long end, List<Delivery> entries, List<Diagnostic> diagnostics, boolean timedOut) {
    }

    /**
     * One entry ready for handoff, with the live policy in force after the delegation-depth
     * check. An entry whose lineage cannot be verified is delivered under {@code confirm}
     * with the problem stated.
     */
    @Serdeable(naming = SnakeCaseStrategy.class)
    record Delivery(EntryMetadata metadata, String body, long start, long end,
                    DeliveryPolicy effectiveLive, @Nullable String lineageProblem) {

        static Delivery of(Entry entry, Ancestry ancestry, EntryIndex index) {
            DeliveryPolicy live;
            String problem = null;
            try {
                Lineage lineage = ancestry.trace(entry.metadata(), index);
                live = lineage.effectiveLive(entry.metadata().delivery());
            } catch (InvalidLineageException e) {
                live = DeliveryPolicy.CONFIRM;
                problem = e.getMessage();
            }
            return new Delivery(entry.metadata(), entry.body(), entry.start(), entry.end(), live, problem);
        }
    }
}
