package com.moltenbits.sideband.install;

import java.nio.file.Path;

/**
 * Puts the client-side pieces in place: both skills, carried inside the executable, and
 * the Claude Code prompt hook for a repository. Idempotent; never touches files it does
 * not own beyond merging one hook entry into a settings file it can parse.
 */
public interface Installer {

    /** Installs or refreshes the skills under {@code homeDir} and the hook for {@code projectDir}. */
    InstallReport install(Path homeDir, Path projectDir);

    /** Reports the state of the same pieces without changing anything. */
    InstallReport inspect(Path homeDir, Path projectDir);
}
