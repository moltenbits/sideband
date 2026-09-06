package com.moltenbits.sideband.command;

import io.micronaut.serde.ObjectMapper;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;

/** Every command prints exactly one JSON document on stdout. */
final class Output {

    private Output() {
    }

    static void print(CommandSpec spec, ObjectMapper json, Object value) throws IOException {
        spec.commandLine().getOut().println(json.writeValueAsString(value));
    }
}
