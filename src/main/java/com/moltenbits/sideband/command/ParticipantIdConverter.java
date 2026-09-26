package com.moltenbits.sideband.command;

import com.moltenbits.sideband.protocol.ParticipantId;
import picocli.CommandLine.ITypeConverter;

/** Lets Picocli parse participant option values: {@code operator} or a client instance such as {@code claude} or {@code claude:fable}. */
final class ParticipantIdConverter implements ITypeConverter<ParticipantId> {

    @Override
    public ParticipantId convert(String value) {
        return new ParticipantId(value);
    }
}
