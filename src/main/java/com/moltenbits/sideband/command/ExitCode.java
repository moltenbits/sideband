package com.moltenbits.sideband.command;

import com.moltenbits.sideband.ancestry.InvalidLineageException;
import com.moltenbits.sideband.home.NotARepositoryException;
import com.moltenbits.sideband.journal.LockTimeoutException;
import com.moltenbits.sideband.protocol.InvalidEntryException;
import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.ParseResult;

/** The stable exit codes every Sideband command uses, and the mapping from failures to them. */
public final class ExitCode {

    public static final int OK = 0;
    public static final int INVALID_INPUT = 2;
    public static final int NOT_A_REPOSITORY = 3;
    public static final int LOCK_CONTENTION = 4;
    public static final int IO_FAILURE = 5;
    public static final int TIMED_OUT = 6;

    private ExitCode() {
    }

    /** Prints the failure to stderr and exits with the code that describes it. */
    public static final IExecutionExceptionHandler HANDLER = ExitCode::handle;

    private static int handle(Exception failure, CommandLine command, ParseResult parseResult) {
        command.getErr().println(command.getColorScheme().errorText("sideband: " + failure.getMessage()));
        return switch (failure) {
            case NotARepositoryException e -> NOT_A_REPOSITORY;
            case LockTimeoutException e -> LOCK_CONTENTION;
            case InvalidEntryException e -> INVALID_INPUT;
            case InvalidLineageException e -> INVALID_INPUT;
            case IllegalArgumentException e -> INVALID_INPUT;
            default -> IO_FAILURE;
        };
    }
}
