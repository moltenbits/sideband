package com.moltenbits.sideband.command;

import io.micronaut.core.annotation.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Bodies arrive through a file or standard input, never as a command-line argument. */
final class Bodies {

    private Bodies() {
    }

    static String read(@Nullable Path bodyFile) throws IOException {
        String body = bodyFile != null
                ? Files.readString(bodyFile, UTF_8)
                : new String(System.in.readAllBytes(), UTF_8);
        if (body.isBlank()) {
            throw new IllegalArgumentException("the body is empty");
        }
        return body;
    }
}
