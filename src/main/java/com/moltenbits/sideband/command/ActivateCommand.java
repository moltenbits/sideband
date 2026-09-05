package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.recipient.Activation;
import com.moltenbits.sideband.recipient.RecipientState;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.serde.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * Starts a Sideband session for a role: initializes state, sets the startup watermark,
 * and prints the backlog the human must decide on. The listener starts from
 * {@code session.watermark_end}.
 */
@Command(name = "activate", description = "Start a session for a role, establish its watermark, and list its backlog")
@Prototype
public class ActivateCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--role", required = true, description = "claude or codex")
    Role role;

    @Option(names = "--session-id", description = "The host's identifier for this session; for codex, defaults to $CODEX_THREAD_ID")
    String sessionId;

    @Option(names = "--parent-pid", description = "The host process, so a dead session can be superseded automatically")
    Long parentPid;

    @Option(names = "--replace", description = "Supersede a live session that already owns the role")
    boolean replace;

    private final SidebandHome home;
    private final RecipientState recipients;
    private final ObjectMapper json;

    ActivateCommand(SidebandHome home, RecipientState recipients, ObjectMapper json) {
        this.home = home;
        this.recipients = recipients;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        String id = sessionId != null ? sessionId : System.getenv(role == Role.CODEX ? "CODEX_THREAD_ID" : "CLAUDE_SESSION_ID");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("--session-id is required" + (role == Role.CODEX ? " (CODEX_THREAD_ID is not set)" : ""));
        }
        Activation activation = recipients.activate(repository.stateDirectory(home), role, id, parentPid, replace);
        Output.print(spec, json, activation);
        return ExitCode.OK;
    }
}
