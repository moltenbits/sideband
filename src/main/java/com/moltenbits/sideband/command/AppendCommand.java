package com.moltenbits.sideband.command;

import com.moltenbits.sideband.ancestry.Ancestry;
import com.moltenbits.sideband.ancestry.EntryIndex;
import com.moltenbits.sideband.capture.Captured;
import com.moltenbits.sideband.capture.HumanCapture;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.project.Projects;
import com.moltenbits.sideband.protocol.Delivery;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.MessageType;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.push.Pushes;
import com.moltenbits.sideband.protocol.Route;
import com.moltenbits.sideband.session.Sessions;
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
 * Adds one entry to the discussion. The author is the calling instance, by the commands' rule
 * of REQUIREMENTS.md 9.5a, unless {@code --from operator} says the entry is the operator's own
 * words, which are routed by their first token and recorded with the calling instance as
 * {@code via}. A session that holds no instance is refused. An agent's actionable entry to
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

    @Option(names = "--from",
            description = "Author: operator for the operator's own words (routed by their first token), otherwise the calling client")
    String from;

    @Option(names = "--via", hidden = true,
            description = "With --from operator: override the agent detected from the environment")
    String via;

    @Option(names = "--to", arity = "1..*",
            description = "Recipients: an agent of this project by name (astra), one of another project as project/name (listenly/astra), or operator. With --reply-to it defaults to the author of the entry being answered")
    List<String> to;

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
    private final Projects projects;
    private final HostEnvironment host;
    private final Journal journal;
    private final Ancestry ancestry;
    private final HumanCapture capture;
    private final Pushes pushes;
    private final Sessions sessions;
    private final ObjectMapper json;

    AppendCommand(SidebandHome home, Projects projects, HostEnvironment host, Journal journal, Ancestry ancestry,
                  HumanCapture capture, Pushes pushes, Sessions sessions, ObjectMapper json) {
        this.home = home;
        this.projects = projects;
        this.host = host;
        this.sessions = sessions;
        this.journal = journal;
        this.ancestry = ancestry;
        this.capture = capture;
        this.pushes = pushes;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        boolean operator = ParticipantId.OPERATOR_ID.equals(from);
        if (via != null && !operator) {
            throw new IllegalArgumentException("--via only applies with --from operator; an agent entry's author is --from or the calling client");
        }
        if (operator) {
            return appendOperator();
        }
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
        String project = repository.project(home, projects).name();
        Path stateDirectory = home.stateDirectory();
        ParticipantId author = from != null ? registered(stateDirectory, Caller.client(from, project, "--from"))
                : Caller.identify(host, sessions, stateDirectory, project, "--from");
        List<ParticipantId> recipients = to == null ? List.of()
                : to.stream().map(address -> registered(stateDirectory, ParticipantId.address(address, project))).toList();
        EntryIndex index = id -> journal.find(stateDirectory, id).map(Entry::metadata);
        EntryMetadata answered = replyTo == null ? null : index.find(replyTo).orElse(null);
        if (replyTo != null && answered == null) {
            throw new IllegalArgumentException("--reply-to names no entry in this discussion: " + replyTo);
        }
        if (recipients.isEmpty()) {
            if (answered == null) {
                throw new IllegalArgumentException("--to is required unless --reply-to names the entry being answered");
            }
            recipients = List.of(answered.from()); // an answer goes to whoever asked, in whichever project
        }
        Draft draft = new Draft(author, null, recipients, type, Route.forRecipients(recipients),
                replyTo, causedBy, actionable, Delivery.DEFAULT, body);
        checkLineage(index, draft);
        Entry entry = journal.append(stateDirectory, draft);
        Output.print(spec, json, Captured.of(entry, pushes.deliver(stateDirectory, entry)));
        return ExitCode.OK;
    }

    /** The operator's own words: routed by their first token, never linked, always a request. */
    private int appendOperator() throws IOException {
        if ((to != null && !to.isEmpty()) || replyTo != null || causedBy != null || expectsReply != null
                || (type != null && type != MessageType.REQUEST)) {
            throw new IllegalArgumentException("--from operator takes only the body: recipients come from its first token, and it is always a request");
        }
        String body = Bodies.read(bodyFile);
        String project = repository.project(home, projects).name();
        Path stateDirectory = home.stateDirectory();
        ParticipantId client = via != null ? Caller.client(via, project, "--via")
                : Caller.identify(host, sessions, stateDirectory, project, "--via");
        Captured captured = capture.capture(stateDirectory, client, body);
        Output.print(spec, json, captured);
        if (captured.unrouted() != null) {
            spec.commandLine().getErr().println("sideband append: recorded " + captured.metadata().id() + " for " + client
                    + " only: " + captured.unrouted() + " is not a Sideband project, so the route it asked for failed");
        }
        return ExitCode.OK;
    }

    /** The participant, refused when it belongs to a project nobody registered. */
    private ParticipantId registered(Path stateDirectory, ParticipantId participant) {
        participant.project().filter(name -> projects.named(stateDirectory, name).isEmpty()).ifPresent(name -> {
            throw new IllegalArgumentException(name + " is not a Sideband project: no project is registered under that name");
        });
        return participant;
    }

    /** The draft has no identifier yet, so trace from a stand-in with the same links. */
    private void checkLineage(EntryIndex index, Draft draft) {
        EntryMetadata candidate = new EntryMetadata(DRAFT_ID, OffsetDateTime.MIN, draft.from(), draft.via(),
                draft.to(), draft.type(), draft.route(), draft.replyTo(), draft.causedBy(), draft.expectsReply(),
                draft.delivery());
        ancestry.trace(candidate, index);
    }
}
