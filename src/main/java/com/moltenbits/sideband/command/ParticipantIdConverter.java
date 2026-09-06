package com.moltenbits.sideband.command;

import com.moltenbits.sideband.protocol.ParticipantId;
import picocli.CommandLine.ITypeConverter;

/** Lets Picocli parse {@code claude}, {@code codex}, or {@code operator} option values. */
final class ParticipantIdConverter implements ITypeConverter<ParticipantId> {

    @Override
    public ParticipantId convert(String value) {
        return new ParticipantId(value);
    }
}
