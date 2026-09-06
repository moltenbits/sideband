package com.moltenbits.sideband.protocol;

import io.micronaut.core.type.Argument;
import io.micronaut.serde.Decoder;
import io.micronaut.serde.Encoder;
import io.micronaut.serde.Serde;
import jakarta.inject.Singleton;

import java.io.IOException;

/** Reads and writes a {@link ParticipantId} as a JSON string. */
@Singleton
final class ParticipantIdSerde implements Serde<ParticipantId> {

    @Override
    public void serialize(Encoder encoder, EncoderContext context, Argument<? extends ParticipantId> type, ParticipantId value)
            throws IOException {
        encoder.encodeString(value.value());
    }

    @Override
    public ParticipantId deserialize(Decoder decoder, DecoderContext context, Argument<? super ParticipantId> type)
            throws IOException {
        return new ParticipantId(decoder.decodeString());
    }
}
