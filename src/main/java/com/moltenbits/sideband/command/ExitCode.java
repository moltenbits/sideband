package com.moltenbits.sideband.command;

import com.moltenbits.sideband.ancestry.InvalidLineageException;
import com.moltenbits.sideband.store.BusyException;
import com.moltenbits.sideband.protocol.InvalidEntryException;
import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.ParseResult;

/** The stable exit codes every Sideband command uses, and the mapping from failures to them. */
public final class ExitCode {

    public static final int OK = 0;
    public static final int INVALID_INPUT = 2;
    /** Retired: every directory has a state location now. Kept so later codes keep their numbers. */
    public static final int NOT_A_REPOSITORY = 3;
    public static final int LOCK_CONTENTION = 4;
    public static final int IO_FAILURE = 5;
    public static final int TIMED_OUT = 6;
    /** Retired: whoever joins as a role last holds it. Kept so the numbers stay stable. */
    public static final int ALREADY_ACTIVE = 7;

    private ExitCode() {
    }

    /** Prints the failure to stderr and exits with the code that describes it. */
    public static final IExecutionExceptionHandler HANDLER = ExitCode::handle;

    private static int handle(Exception failure, CommandLine command, ParseResult parseResult) {
        command.getErr().println(command.getColorScheme().errorText("sideband: " + failure.getMessage()));
        return switch (failure) {
            case BusyException e -> LOCK_CONTENTION;
            case InvalidEntryException e -> INVALID_INPUT;
            case InvalidLineageException e -> INVALID_INPUT;
            case IllegalArgumentException e -> INVALID_INPUT;
            default -> IO_FAILURE;
        };
    }
}
