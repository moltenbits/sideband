package com.moltenbits.sideband.journal

import com.moltenbits.sideband.Fixtures
import com.moltenbits.sideband.protocol.EntryMetadata
import com.moltenbits.sideband.protocol.MessageType
import com.moltenbits.sideband.protocol.Route
import io.micronaut.context.ApplicationContext
import io.micronaut.serde.ObjectMapper
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class EntryCodecSpec extends Specification {

    static final String BODY = "@all independently review the proposed database migration."

    @Shared @AutoCleanup ApplicationContext context = ApplicationContext.run()

    EntryCodec codec = new EntryCodec(context.getBean(ObjectMapper))

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        parts.each { out.write(it) }
        out.toByteArray()
    }

    static byte[] head(byte[] bytes, int count) {
        Arrays.copyOf(bytes, count)
    }

    byte[] encode(EntryMetadata metadata, String body) {
        codec.encode(metadata, body)
    }

    byte[] entry(String body, Map overrides = [:]) {
        encode(Fixtures.metadata([bodyBytes: EntryCodec.bodyLength(body)] + overrides), body)
    }

    void "encodes exactly the requirements example"() {
        expect:
        new String(entry(BODY), StandardCharsets.UTF_8) == '''\
<!-- sideband:v1
{"id":"019a","created_at":"2026-09-02T16:42:00-05:00","from":"human:james","via":"claude","to":["claude","codex"],"type":"request","route":"broadcast","reply_to":null,"caused_by":null,"expects_reply":true,"heartbeat_seconds":null,"delivery":{"live":"auto","backlog":"confirm"},"body_bytes":58}
-->

## James → Claude + Codex (via Claude)

@all independently review the proposed database migration.
<!-- /sideband -->
'''
    }

    void "an agent reply heading has no via"() {
        given:
        def reply = Fixtures.metadata(id: "019c", from: Fixtures.CODEX, via: null, to: [Fixtures.CLAUDE],
                type: MessageType.REPLY, route: Route.DIRECT, replyTo: "019b", expectsReply: false, bodyBytes: 4)

        expect:
        new String(encode(reply, "done"), StandardCharsets.UTF_8).contains("\n## Codex → Claude\n\ndone\n<!-- /sideband -->\n")
    }

    void "encoding refuses a body_bytes that does not match the body"() {
        when:
        encode(Fixtures.metadata(bodyBytes: 3), BODY)

        then:
        thrown(IllegalArgumentException)
    }

    void "a single entry parses back with its offsets"() {
        given:
        byte[] bytes = entry(BODY)

        when:
        Read read = codec.parse(bytes, 1000)

        then:
        read.entries().size() == 1
        read.entries()[0].body() == BODY
        read.entries()[0].metadata() == Fixtures.metadata()
        read.entries()[0].start() == 1000
        read.entries()[0].end() == 1000 + bytes.length
        read.end() == 1000 + bytes.length
        read.diagnostics().isEmpty()
    }

    void "bodies round-trip verbatim"() {
        when:
        Read read = codec.parse(entry(body), 0)

        then:
        read.entries()*.body() == [body]
        read.diagnostics().isEmpty()

        where:
        body << [
                "one line",
                "trailing newline\n",
                "two trailing newlines\n\n",
                "## a heading-looking line\n\nand a paragraph",
                "<!-- an html comment --> inside",
                "the closing marker on its own line:\n<!-- /sideband -->\nfollowed by more",
                "héllo → wörld ✓ 日本語",
                "   leading whitespace",
                "a" * 10000,
        ]
    }

    void "a body containing a complete example entry stays one message and the next entry still parses"() {
        given:
        String nested = new String(entry("inner"), StandardCharsets.UTF_8)
        byte[] outer = entry("Here is an example entry:\n\n" + nested + "\nEnd of example.", [id: "outer"])
        byte[] following = entry("next", [id: "next"])
        byte[] bytes = cat(outer, following)

        when:
        Read read = codec.parse(bytes, 0)

        then:
        read.entries()*.metadata()*.id() == ["outer", "next"]
        read.entries()[0].body().contains(nested)
        read.diagnostics().isEmpty()
        read.end() == bytes.length
    }

    void "several entries parse in physical order"() {
        given:
        byte[] bytes = cat(entry("one", [id: "a"]), entry("two", [id: "b"]), entry("three", [id: "c"]))

        expect:
        codec.parse(bytes, 0).entries()*.body() == ["one", "two", "three"]
    }

    void "an incomplete trailing entry is withheld and the end offset stops before it"() {
        given:
        byte[] complete = entry("one", [id: "a"])
        byte[] partial = entry("two two two", [id: "b"])

        when:
        Read read = codec.parse(cat(complete, head(partial, cut)), 0)

        then:
        read.entries()*.body() == ["one"]
        read.end() == complete.length
        read.diagnostics().isEmpty()

        where:
        cut << [5, 20, 60, 200, 280]
    }

    void "a partially written opener at the end is treated as incomplete"() {
        given:
        byte[] complete = entry("one", [id: "a"])
        byte[] bytes = cat(complete, "<!-- side".bytes)

        when:
        Read read = codec.parse(bytes, 0)

        then:
        read.entries().size() == 1
        read.end() == complete.length
        read.diagnostics().isEmpty()
    }

    void "malformed entries produce one diagnostic each and later entries still parse"() {
        given:
        byte[] good = entry("good", [id: "g"])
        byte[] bytes = cat(corrupt(entry("bad", [id: "b"])), good)

        when:
        Read read = codec.parse(bytes, 0)

        then:
        read.entries()*.body() == ["good"]
        read.diagnostics().size() == 1
        read.diagnostics()[0].offset() == 0
        read.diagnostics()[0].reason().contains(reason)
        read.end() == bytes.length

        where:
        corrupt                                                                           | reason
        ({ byte[] b -> replace(b, '"type":"request"', '"type":"control"') })           | "invalid metadata"
        ({ byte[] b -> replace(b, '"body_bytes":3', '"body_bytes":2') })                   | "body_bytes"
        ({ byte[] b -> replace(b, '"body_bytes":3', '"body_bytes":99') })                  | "body_bytes"
        ({ byte[] b -> replace(b, 'sideband:v1\n', 'sideband:v2\n') })                     | "unsupported protocol version 'v2'"
        ({ byte[] b -> replace(b, '-->\n\n## ', '-->\n## ') })                              | "blank line before heading"
        ({ byte[] b -> replace(b, '\n## James', '\nJames') })                               | "missing heading"
        ({ byte[] b -> replace(b, '{"id"', '{"id') })                                       | "invalid metadata"
    }

    void "the diagnostic for wrong body_bytes points at the entry, not into the body"() {
        given:
        byte[] bytes = cat(replace(entry("abc", [id: "b"]), '"body_bytes":3', '"body_bytes":99'), entry("ok", [id: "g"]))

        expect:
        codec.parse(bytes, 500).diagnostics()*.offset() == [500L]
    }

    void "a body that is not valid UTF-8 is a diagnostic"() {
        given:
        byte[] bytes = entry("abc", [id: "b"])
        int bodyAt = indexOf(bytes, "\n\nabc\n".bytes) + 2
        bytes[bodyAt] = (byte) 0xFF

        expect:
        codec.parse(bytes, 0).diagnostics()*.reason() == ["body is not valid UTF-8"]
    }

    void "unframed bytes between and after entries are reported and skipped"() {
        given:
        byte[] bytes = cat("stray spike bytes\n<!-- /sideband -->\n".bytes, entry("one", [id: "a"]), "trailing junk\n".bytes)

        when:
        Read read = codec.parse(bytes, 0)

        then:
        read.entries()*.body() == ["one"]
        read.diagnostics()*.reason() == ["unframed bytes before the next entry", "unframed bytes at the end of the journal"]
        read.end() == bytes.length
    }

    void "an abort marker closes an incomplete fragment so readers move past it"() {
        given:
        byte[] full = entry(body, [id: "f"])
        byte[] fragment = head(full, keep < 0 ? full.length + keep : keep)
        byte[] bytes = cat(fragment, "\n".bytes, EntryCodec.ABORT_LINE, entry("after", [id: "a"]))

        when:
        Read read = codec.parse(bytes, 0)

        then:
        read.entries()*.body() == ["after"]
        read.diagnostics().size() == 1
        read.diagnostics()[0].reason().contains(reason)
        read.end() == bytes.length

        where:
        body             | keep  | reason
        "x" * 2000       | -1900 | "abort"           // cut deep in a long body: still incomplete when the marker arrives
        "never finished" | -12   | "closing marker"  // cut inside the terminator: malformed outright
        "never finished" | 40    | "invalid metadata" // cut inside the metadata line
    }

    void "whitespace between entries is not a diagnostic"() {
        given:
        byte[] bytes = cat("\n\n".bytes, entry("one", [id: "a"]), "\n \n".bytes, entry("two", [id: "b"]))

        expect:
        codec.parse(bytes, 0).diagnostics().isEmpty()
        codec.parse(bytes, 0).entries().size() == 2
    }

    void "an empty input reads as empty"() {
        expect:
        codec.parse(new byte[0], 7) == Read.empty(7)
    }

    static byte[] replace(byte[] bytes, String from, String to) {
        new String(bytes, StandardCharsets.UTF_8).replace(from, to).getBytes(StandardCharsets.UTF_8)
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        (0..(haystack.length - needle.length)).find { int i ->
            Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)
        } ?: -1
    }
}
