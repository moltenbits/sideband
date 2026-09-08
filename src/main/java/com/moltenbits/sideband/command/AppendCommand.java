package com.moltenbits.sideband.command;

import com.moltenbits.sideband.ancestry.Ancestry;
import com.moltenbits.sideband.ancestry.EntryIndex;
import com.moltenbits.sideband.capture.Captured;
import com.moltenbits.sideband.capture.HumanCapture;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.protocol.Delivery;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.MessageType;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.push.Pushes;
import com.moltenbits.sideband.protocol.Route;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.serde.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Adds one entry to the discussion. The author is the calling client unless {@code --from
 * operator} says the entry is the operator's own words, which are routed by their first
 * token and recorded with the calling client as {@code via}. An agent's actionable entry to
 * another client must trace to a human-authored one; the command refuses one that does not.
 */
@Command(name = "append", description = "Add an entry to the Sideband discussion: a request, reply, status, or ack from this client, or with --from operator the operator's own words", mixinStandardHelpOptions = true)
@Prototype
public class AppendCommand implements Callable<Integer> {

    /** Stands in for the identifier the journal will assign, so lineage errors read naturally. */
    private static final String DRAFT_ID = "the-new-entry";

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--from", converter = ParticipantIdConverter.class,
            description = "Author: operator for the operator's own words (routed by their first token), otherwise the calling client")
    ParticipantId from;

    @Option(names = "--via", hidden = true, description = "With --from operator: override the client detected from the environment")
    Role via;

    @Option(names = "--to", arity = "1..*", converter = ParticipantIdConverter.class,
            description = "Recipients: claude, codex, or operator. With --reply-to it defaults to the author of the entry being answered")
    List<ParticipantId> to;

    @Option(names = "--type", description = "request, reply, status, or ack (required unless --from operator, whose entries are requests)")
    MessageType type;

    @Option(names = "--reply-to", description = "The entry this directly answers")
    String replyTo;

    @Option(names = "--caused-by", description = "The immediate communication that initiated this message")
    String causedBy;

    @Option(names = "--expects-reply", arity = "1", paramLabel = "true|false",
            description = "Whether recipients should treat this as actionable (default: true for requests, false otherwise)")
    Boolean expectsReply;

    @Option(names = "--body-file", description = "File holding the body; standard input is read when omitted (an ack may have none)")
    Path bodyFile;

    private final SidebandHome home;
    private final HostEnvironment host;
    private final Journal journal;
    private final Ancestry ancestry;
    private final HumanCapture capture;
    private final Pushes pushes;
    private final ObjectMapper json;

    AppendCommand(SidebandHome home, HostEnvironment host, Journal journal, Ancestry ancestry, HumanCapture capture,
                  Pushes pushes, ObjectMapper json) {
        this.home = home;
        this.host = host;
        this.journal = journal;
        this.ancestry = ancestry;
        this.capture = capture;
        this.pushes = pushes;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        boolean operator = from != null && from.isHuman();
        if (via != null && !operator) {
            throw new IllegalArgumentException("--via only applies with --from operator; an agent entry's author is --from or the calling client");
        }
        if (operator) {
            return appendOperator(via != null ? via : host.requireRole("--via"));
        }
        Role author = from != null ? from.role().orElseThrow() : host.requireRole("--from");
        if (type == null) {
            throw new IllegalArgumentException("--type is required: request, reply, status, or ack");
        }
        boolean actionable;
        String body;
        if (type == MessageType.ACK) {
            if (expectsReply != null && expectsReply) {
                throw new IllegalArgumentException("an ack never expects a reply");
            }
            actionable = false;
            body = bodyFile == null && System.in.available() == 0 ? "received" : Bodies.read(bodyFile);
            if (body.isBlank()) {
                body = "received";
            }
        } else {
            actionable = expectsReply != null ? expectsReply : type == MessageType.REQUEST;
            body = Bodies.read(bodyFile);
        }
        Path stateDirectory = repository.stateDirectory(home);
        EntryIndex index = id -> journal.find(stateDirectory, id).map(Entry::metadata);
        EntryMetadata answered = replyTo == null ? null : index.find(replyTo).orElse(null);
        if (replyTo != null && answered == null) {
            throw new IllegalArgumentException("--reply-to names no entry in this discussion: " + replyTo);
        }
        if (to == null || to.isEmpty()) {
            if (answered == null) {
                throw new IllegalArgumentException("--to is required unless --reply-to names the entry being answered");
            }
            to = List.of(answered.from()); // an answer goes to whoever asked
        }
        Draft draft = new Draft(ParticipantId.of(author), null, to, type, Route.forRecipients(to),
                replyTo, causedBy, actionable, Delivery.DEFAULT, body);
        checkLineage(index, draft);
        Entry entry = journal.append(stateDirectory, draft);
        Output.print(spec, json, Captured.of(entry, pushes.deliver(stateDirectory, entry)));
        return ExitCode.OK;
    }

    /** The operator's own words: routed by their first token, never linked, always a request. */
    private int appendOperator(Role client) throws IOException {
        if ((to != null && !to.isEmpty()) || replyTo != null || causedBy != null || expectsReply != null
                || (type != null && type != MessageType.REQUEST)) {
            throw new IllegalArgumentException("--from operator takes only the body: recipients come from its first token, and it is always a request");
        }
        String body = Bodies.read(bodyFile);
        Output.print(spec, json, capture.capture(repository.stateDirectory(home), client, body));
        return ExitCode.OK;
    }

    /** The draft has no identifier yet, so trace from a stand-in with the same links. */
    private void checkLineage(EntryIndex index, Draft draft) {
        EntryMetadata candidate = new EntryMetadata(DRAFT_ID, OffsetDateTime.MIN, draft.from(), draft.via(),
                draft.to(), draft.type(), draft.route(), draft.replyTo(), draft.causedBy(), draft.expectsReply(),
                draft.delivery());
        ancestry.trace(candidate, index);
    }
}
