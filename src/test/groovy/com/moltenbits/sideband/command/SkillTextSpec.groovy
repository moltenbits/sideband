package com.moltenbits.sideband.command

import com.moltenbits.sideband.SidebandCommand
import picocli.CommandLine

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher

/**
 * The skill texts describe the executable to a model that cannot check them, so every
 * `sideband <command>` they name must be a command the executable has. A renamed or
 * removed command fails here instead of in a conversation.
 */
class SkillTextSpec extends CommandSpec {

    static final List<Path> TEXTS = ["claude", "codex"].collectMany { client ->
        [Path.of("skills", client, "SKILL.md"), Path.of("skills", client, "INSTRUCTIONS.md")]
    }

    /** `sideband <word> [<word>]` inside backticks; a placeholder such as `<command>` or an option does not count. */
    static final String MENTION = /`(?:! )?sideband ([a-z][a-z-]*)(?: ([a-z][a-z-]*))?[^`]*`/

    void "every command the skill texts name exists in the executable"() {
        given:
        CommandLine cli = SidebandCommand.commandLine(context)
        Map<String, CommandLine> commands = cli.subcommands

        expect:
        TEXTS.every { Files.exists(it) }
        unknownMentions(commands) == []
    }

    static List<String> unknownMentions(Map<String, CommandLine> commands) {
        TEXTS.collectMany { path ->
            Matcher m = (Files.readString(path) =~ MENTION)
            List<String> unknown = []
            while (m.find()) {
                String first = m.group(1)
                String second = m.group(2)
                CommandLine command = commands[first]
                if (command == null) {
                    unknown << "${path}: sideband ${first}".toString()
                } else if (second != null && !command.subcommands.isEmpty() && !command.subcommands.containsKey(second)) {
                    unknown << "${path}: sideband ${first} ${second}".toString()
                }
            }
            unknown
        }
    }
}
