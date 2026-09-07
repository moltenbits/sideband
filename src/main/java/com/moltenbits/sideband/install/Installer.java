package com.moltenbits.sideband.install;

import com.moltenbits.sideband.protocol.Role;

import java.nio.file.Path;

/**
 * Puts the client-side pieces in place: a skill stub for each client that defers to this
 * executable, and the Claude Code prompt hook entry for a repository, pointing at this
 * executable. Idempotent; never touches files it does not own beyond merging one hook
 * entry into a settings file it can parse.
 */
public interface Installer {

    /** Installs or refreshes the skills under {@code homeDir} and the hook for {@code projectDir}. */
    InstallReport install(Path homeDir, Path projectDir);

    /** Reports the state of the same pieces without changing anything. */
    InstallReport inspect(Path homeDir, Path projectDir);

    /**
     * Whether Claude Code will deliver a pushed envelope, judged from the settings files the
     * executable can read; {@code installed} means yes. The same item {@link #inspect} reports.
     */
    InstallReport.Item inbound(Path homeDir, Path projectDir);

    /** The adapter instructions embedded for a client, served to the installed skill stub. */
    String instructions(Role client);

    /**
     * Replaces a client's installed stub with the full instructions so the operator can edit
     * them. An ejected skill is theirs from then on: {@link #install} leaves it alone and it
     * no longer updates with the executable. Deleting it and rerunning {@code init} goes back.
     *
     * @throws IllegalArgumentException when the skill is already ejected and {@code force} is false
     */
    InstallReport.Item eject(Path homeDir, Role client, boolean force);
}
