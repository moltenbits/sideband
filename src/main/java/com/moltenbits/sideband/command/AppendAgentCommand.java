package com.moltenbits.sideband.command;

import com.moltenbits.sideband.ancestry.Ancestry;
import com.moltenbits.sideband.ancestry.EntryIndex;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.journal.Read;
import com.moltenbits.sideband.protocol.Delivery;
import com.moltenbits.sideband.protocol.Draft;
import com.moltenbits.sideband.protocol.EntryMetadata;
import com.moltenbits.sideband.protocol.MessageType;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.protocol.Route;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.serde.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Journals an agent-authored request, reply, or status. Actionable messages to another
 * client must trace to a human-authored entry; the command refuses to append one that does not.
 */
@Command(name = "append-agent", description = "Journal an agent-authored request, reply, or status")
@Prototype
public class AppendAgentCommand implements Callable<Integer> {

    /** Stands in for the identifier the journal will assign, so lineage errors read naturally. */
    private static final String DRAFT_ID = "the-new-entry";

    @Spec
    CommandSpec spec;

    @Option(names = "--repo", description = "A directory inside the repository (default: current directory)")
    Path repository = Path.of(System.getProperty("user.dir"));

    @Option(names = "--from", required = true, description = "The authoring client: claude or codex")
    Role from;

    @Option(names = "--to", required = true, arity = "1..*", converter = ParticipantIdConverter.class,
            description = "Recipients: claude, codex, or human:<id>")
    List<ParticipantId> to;

    @Option(names = "--type", required = true, description = "request, reply, or status")
    MessageType type;

    @Option(names = "--reply-to", description = "The entry this directly answers")
    String replyTo;

    @Option(names = "--caused-by", description = "The immediate communication that initiated this message")
    String causedBy;

    @Option(names = "--expects-reply", arity = "1", paramLabel = "true|false",
            description = "Whether recipients should treat this as actionable (default: true for requests, false otherwise)")
    Boolean expectsReply;

    @Option(names = "--body-file", description = "File holding the body; standard input is read when omitted")
    Path bodyFile;

    private final SidebandHome home;
    private final Journal journal;
    private final Ancestry ancestry;
    private final ObjectMapper json;

    AppendAgentCommand(SidebandHome home, Journal journal, Ancestry ancestry, ObjectMapper json) {
        this.home = home;
        this.journal = journal;
        this.ancestry = ancestry;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        if (type == MessageType.INSTRUCTION) {
            throw new IllegalArgumentException("only a human may author an instruction; use capture-human");
        }
        String body = Bodies.read(bodyFile);
        boolean actionable = expectsReply != null ? expectsReply : type == MessageType.REQUEST;
        Draft draft = new Draft(ParticipantId.of(from), null, to, type, Route.forRecipients(to),
                replyTo, causedBy, actionable, Delivery.DEFAULT, body);
        Path file = home.initialize(repository).resolve(Journal.FILE_NAME);
        checkLineage(file, draft);
        Entry entry = journal.append(file, draft);
        spec.commandLine().getOut().println(json.writeValueAsString(entry));
        return ExitCode.OK;
    }

    /** The draft has no identifier yet, so trace from a stand-in with the same links. */
    private void checkLineage(Path file, Draft draft) {
        Read all = journal.readCompleteFrom(file, 0);
        Map<String, EntryMetadata> byId = new HashMap<>();
        for (Entry entry : all.entries()) {
            byId.put(entry.metadata().id(), entry.metadata());
        }
        EntryIndex index = id -> Optional.ofNullable(byId.get(id));
        EntryMetadata candidate = new EntryMetadata(DRAFT_ID, OffsetDateTime.MIN, draft.from(), draft.via(),
                draft.to(), draft.type(), draft.route(), draft.replyTo(), draft.causedBy(), draft.expectsReply(),
                draft.delivery(), 0);
        ancestry.trace(candidate, index);
    }
}
