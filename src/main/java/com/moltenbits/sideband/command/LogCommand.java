package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.journal.Page;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Nullable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Renders the discussion as Markdown for a person to read: the heading names author and
 * recipients, a short list gives the metadata, and the body follows verbatim. By default it
 * prints the newest page, newest first, and names the page before it; {@code --after} reads
 * forward instead, and {@code --id} and {@code --trace} look entries up. This is the store's
 * readable form now that nothing on disk is; it is the one command whose output is prose
 * rather than JSON.
 */
@Command(name = "log", description = "Print the discussion as Markdown: the newest entries first, a page at a time; "
        + "--after reads forward, --id and --trace look entries up", mixinStandardHelpOptions = true)
@Prototype
public class LogCommand implements Callable<Integer> {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final int PAGE_SIZE = 20;

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @ArgGroup
    Selection selection;

    @Option(names = "--limit", paramLabel = "COUNT",
            description = "Print at most this many entries (default for a page: " + PAGE_SIZE + ")")
    Integer limit;

    /** The ways of choosing what to print; at most one applies. */
    static class Selection {

        @Option(names = "--before", paramLabel = "POSITION", description = "Print the page of entries before this position, newest first")
        Long before;

        @Option(names = "--after", paramLabel = "POSITION", description = "Print the entries after this position, oldest first; --after 0 prints them all")
        Long after;

        @Option(names = "--id", paramLabel = "ID", description = "Print only the entry with this id")
        String id;

        @Option(names = "--trace", paramLabel = "ID",
                description = "Print the entry with this id and the entries it came from, oldest first, following caused_by, otherwise reply_to")
        String trace;
    }

    private final SidebandHome home;
    private final Journal journal;

    LogCommand(SidebandHome home, Journal journal) {
        this.home = home;
        this.journal = journal;
    }

    @Override
    public Integer call() {
        Selection chosen = selection == null ? new Selection() : selection;
        if (limit != null && limit < 0) {
            throw new IllegalArgumentException("--limit must not be negative");
        }
        if (limit != null && (chosen.id != null || chosen.trace != null)) {
            throw new IllegalArgumentException("--limit does not apply to --id or --trace");
        }
        if (chosen.after != null && chosen.after < 0) {
            throw new IllegalArgumentException("--after must not be negative");
        }
        if (chosen.before != null && chosen.before < 1) {
            throw new IllegalArgumentException("--before must be at least 1");
        }
        Path stateDirectory = home.locate(repository.directory);
        StringBuilder text = new StringBuilder();
        if (chosen.id != null) {
            text.append(render(lookUp(stateDirectory, chosen.id)));
        } else if (chosen.trace != null) {
            Trace trace = trace(lookUp(stateDirectory, chosen.trace), id -> journal.find(stateDirectory, id));
            if (trace.notice() != null) {
                text.append(trace.notice());
            }
            trace.entries().forEach(entry -> text.append(render(entry)));
        } else if (chosen.after != null) {
            List<Entry> entries = journal.readAfter(stateDirectory, chosen.after).entries();
            if (limit != null && entries.size() > limit) {
                entries = entries.subList(0, limit);
            }
            entries.forEach(entry -> text.append(render(entry)));
        } else if (limit == null || limit > 0) {
            Page page = journal.readBefore(stateDirectory, chosen.before == null ? Long.MAX_VALUE : chosen.before,
                    limit == null ? PAGE_SIZE : limit);
            page.entries().forEach(entry -> text.append(render(entry)));
            if (page.older()) {
                text.append("Earlier entries: `sideband log --before ").append(page.entries().getLast().seq())
                        .append(limit == null ? "" : " --limit " + limit)
                        .append(spec.commandLine().getParseResult().hasMatchedOption("--repo")
                                ? " --repo " + shellQuoted(repository.directory.toAbsolutePath().normalize().toString()) : "")
                        .append("`\n");
            }
        }
        PrintWriter out = spec.commandLine().getOut();
        out.print(text);
        out.flush();
        if (out.checkError()) {
            spec.commandLine().getErr().println("sideband log: could not write the output");
            return ExitCode.IO_FAILURE;
        }
        return ExitCode.OK;
    }

    /** The text as one POSIX shell word, so the command it is part of can be pasted as it is. */
    private static String shellQuoted(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    private Entry lookUp(Path stateDirectory, String id) {
        return journal.find(stateDirectory, id)
                .orElseThrow(() -> new IllegalArgumentException("no entry with id " + id + " in this discussion"));
    }

    /**
     * The entry and the entries it came from, oldest first, following {@link EntryMetadata#cause()}
     * until an entry has no link. A link to an entry that is missing, or already in the trace,
     * ends it early with a notice saying so, and what was reached is still returned.
     */
    static Trace trace(Entry start, Function<String, Optional<Entry>> find) {
        List<Entry> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Entry current = start;
        while (true) {
            chain.addFirst(current);
            seen.add(current.metadata().id());
            String cause = current.metadata().cause();
            if (cause == null) {
                return new Trace(chain, null);
            }
            String last = current.metadata().id();
            if (seen.contains(cause)) {
                return new Trace(chain, "**Incomplete trace.** " + last + " links to " + cause
                        + ", which is already in this trace, so its links loop.\n\n");
            }
            Optional<Entry> parent = find.apply(cause);
            if (parent.isEmpty()) {
                return new Trace(chain, "**Incomplete trace.** " + last + " links to " + cause
                        + ", which is not in this journal, so whatever started it is missing.\n\n");
            }
            current = parent.get();
        }
    }

    /**
     * @param entries the entries, oldest first
     * @param notice  why the trace stopped before an entry with no link, or null when it did not
     */
    record Trace(List<Entry> entries, @Nullable String notice) {
    }

    static String render(Entry entry) {
        EntryMetadata m = entry.metadata();
        StringBuilder text = new StringBuilder();
        text.append(Heading.of(m)).append("\n\n");
        text.append("- position: ").append(entry.seq()).append('\n');
        text.append("- id: ").append(m.id()).append('\n');
        text.append("- created: ").append(TIMESTAMP.format(m.createdAt())).append('\n');
        text.append("- type: ").append(m.type().id()).append(m.expectsReply() ? " (expects a reply)" : "").append('\n');
        text.append("- to: ").append(m.to().stream().map(ParticipantId::value).collect(Collectors.joining(", "))).append('\n');
        if (m.replyTo() != null) {
            text.append("- reply_to: ").append(m.replyTo()).append('\n');
        }
        if (m.causedBy() != null) {
            text.append("- caused_by: ").append(m.causedBy()).append('\n');
        }
        text.append('\n').append(entry.body());
        if (!entry.body().endsWith("\n")) {
            text.append('\n');
        }
        text.append("\n---\n\n");
        return text.toString();
    }
}
