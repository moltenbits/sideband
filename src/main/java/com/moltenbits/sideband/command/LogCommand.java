package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Heading;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.ParticipantId;
import io.micronaut.context.annotation.Prototype;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Renders the discussion as Markdown, oldest first, for a person to read: the heading
 * names author and recipients, a short list gives the metadata, and the body follows
 * verbatim. This is the store's readable form now that nothing on disk is; it is the one
 * command whose output is prose rather than JSON.
 */
@Command(name = "log", description = "Print the discussion as Markdown, oldest first; --after and --limit select a range", mixinStandardHelpOptions = true)
@Prototype
public class LogCommand implements Callable<Integer> {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--after", paramLabel = "POSITION", description = "Start after this position (default: the beginning)")
    long after;

    @Option(names = "--limit", paramLabel = "COUNT", description = "Print at most this many entries")
    Integer limit;

    private final SidebandHome home;
    private final Journal journal;

    LogCommand(SidebandHome home, Journal journal) {
        this.home = home;
        this.journal = journal;
    }

    @Override
    public Integer call() {
        if (after < 0) {
            throw new IllegalArgumentException("--after must not be negative");
        }
        if (limit != null && limit < 0) {
            throw new IllegalArgumentException("--limit must not be negative");
        }
        Path stateDirectory = home.locate(repository.directory);
        List<Entry> entries = journal.readAfter(stateDirectory, after).entries();
        if (limit != null && entries.size() > limit) {
            entries = entries.subList(0, limit);
        }
        PrintWriter out = spec.commandLine().getOut();
        for (Entry entry : entries) {
            out.print(render(entry));
        }
        out.flush();
        return ExitCode.OK;
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
