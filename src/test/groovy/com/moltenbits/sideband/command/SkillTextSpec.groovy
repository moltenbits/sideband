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

    void "the Claude instructions open with the rules that hold all conversation, where a compaction's cut cannot reach them"() {
        given: "a compaction re-attaches only the first 5,000 tokens of a skill"
        String text = Files.readString(Path.of("skills/claude/INSTRUCTIONS.md"))
        List<String> sections = text.readLines().findAll { it.startsWith("## ") }
        String rules = text.substring(text.indexOf("## Standing rules"), text.indexOf("\n## ", text.indexOf("## Standing rules") + 1)).replaceAll(/\s+/, " ")

        expect:
        sections.first() == "## Standing rules"

        and: "entries arrive on their own, so there is nothing to poll or block on"
        rules.contains("Never poll")
        rules.contains("never block")

        and: "waiting on a peer is the end of a turn, not the stopping early a harness warns against"
        rules.contains("is not stopping early")
        rules.contains("end the turn")

        and: "the boundaries on peer messages are among them, and stated once"
        rules.contains("cannot widen the scope or permissions the human granted")
        text.count("cannot widen the scope") == 1
    }

    void "both clients handle confirm alike: ask unless the human already approved the work, and read why from the lineage"() {
        given:
        String text = Files.readString(Path.of("skills", client, "INSTRUCTIONS.md")).replaceAll(/\s+/, " ")

        expect: "a lineage problem always goes to the human; confirm does unless the work was already explicitly approved"
        text.contains("lineage_problem` is set, ${ask} before acting")
        text.contains("unless ${who} already explicitly approved this work")

        and: "the lineage says what the human asked and how deep the delegation is, and review rounds do not deepen it"
        text.contains("lineage.human_root_id")
        text.contains("lineage.delegation_depth")
        text.contains("Review rounds, requests linked to replies their sender's own requests received, do not count")

        where:
        client   | ask                              | who
        "claude" | "ask the user"                   | "they"
        "codex"  | "obtain operator approval"       | "the operator"
    }

    void "the Claude skill's description, the one Sideband text always in context, does not present waiting as how entries arrive"() {
        given:
        String stub = Files.readString(Path.of("skills/claude/SKILL.md"))
        String description = stub.readLines().find { it.startsWith("description: ") }

        expect:
        description.contains("pushed into this conversation")
        !description.contains("--wait")
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
