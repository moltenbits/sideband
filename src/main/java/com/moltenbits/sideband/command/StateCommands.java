package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.protocol.Wire;
import com.moltenbits.sideband.recipient.Cursor;
import com.moltenbits.sideband.recipient.OutgoingStatus;
import com.moltenbits.sideband.recipient.RecipientState;
import com.moltenbits.sideband.recipient.Resolution;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.serde.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/** The cursor transitions an adapter records as it delivers and the parent disposes of entries. */
public final class StateCommands {

    private StateCommands() {
    }

    /** Fields every transition command shares. */
    abstract static class Transition implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Mixin
        Repository repository;

        @Option(names = "--role", required = true, description = "claude or codex")
        Role role;

        @Parameters(arity = "1..*", paramLabel = "ID", description = "Entry identifiers")
        List<String> ids;

        final SidebandHome home;
        final RecipientState recipients;
        final ObjectMapper json;

        Transition(SidebandHome home, RecipientState recipients, ObjectMapper json) {
            this.home = home;
            this.recipients = recipients;
            this.json = json;
        }

        abstract Cursor apply();

        @Override
        public Integer call() throws IOException {
            Output.print(spec, json, apply());
            return ExitCode.OK;
        }
    }

    @Command(name = "mark-seen", description = "Record that entries were summarized or shown to the parent")
    @Prototype
    public static class MarkSeen extends Transition {

        MarkSeen(SidebandHome home, RecipientState recipients, ObjectMapper json) {
            super(home, recipients, json);
        }

        @Override
        Cursor apply() {
            return recipients.markSeen(repository.stateDirectory(home), role, ids);
        }
    }

    @Command(name = "mark-delivered", description = "Record that the host accepted entries for handoff to the parent")
    @Prototype
    public static class MarkDelivered extends Transition {

        MarkDelivered(SidebandHome home, RecipientState recipients, ObjectMapper json) {
            super(home, recipients, json);
        }

        @Override
        Cursor apply() {
            return recipients.markDelivered(repository.stateDirectory(home), role, ids);
        }
    }

    @Command(name = "resolve", description = "Record the parent's disposition of incoming entries")
    @Prototype
    public static class Resolve extends Transition {

        @Option(names = "--as", required = true, description = "acted, dismissed, presented, or originating-turn")
        String resolution;

        Resolve(SidebandHome home, RecipientState recipients, ObjectMapper json) {
            super(home, recipients, json);
        }

        @Override
        Cursor apply() {
            return recipients.resolve(repository.stateDirectory(home), role, ids, wire(Resolution.class, resolution));
        }
    }

    @Command(name = "resolve-outgoing", description = "Record that the parent considers its requests answered or dismissed")
    @Prototype
    public static class ResolveOutgoing extends Transition {

        @Option(names = "--as", required = true, description = "answered or dismissed")
        String status;

        ResolveOutgoing(SidebandHome home, RecipientState recipients, ObjectMapper json) {
            super(home, recipients, json);
        }

        @Override
        Cursor apply() {
            OutgoingStatus outcome = wire(OutgoingStatus.class, status);
            if (outcome == OutgoingStatus.PENDING) {
                throw new IllegalArgumentException("--as must be answered or dismissed");
            }
            return recipients.resolveOutgoing(repository.stateDirectory(home), role, ids, outcome);
        }
    }

    static <E extends Enum<E> & Wire> E wire(Class<E> type, String id) {
        for (E constant : type.getEnumConstants()) {
            if (constant.id().equalsIgnoreCase(id)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("'" + id + "' is not one of "
                + String.join(", ", Arrays.stream(type.getEnumConstants()).map(Wire::id).toList()));
    }
}
