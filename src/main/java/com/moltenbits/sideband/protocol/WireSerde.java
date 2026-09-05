package com.moltenbits.sideband.protocol;

import io.micronaut.core.type.Argument;
import io.micronaut.serde.Decoder;
import io.micronaut.serde.Encoder;
import io.micronaut.serde.Serde;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Reads and writes a {@link Wire} enum by its identifier; an unknown identifier is an error, never a guess. */
abstract class WireSerde<E extends Enum<E> & Wire> implements Serde<E> {

    private final Class<E> type;

    WireSerde(Class<E> type) {
        this.type = type;
    }

    @Override
    public void serialize(Encoder encoder, EncoderContext context, Argument<? extends E> argument, E value) throws IOException {
        encoder.encodeString(value.id());
    }

    @Override
    public E deserialize(Decoder decoder, DecoderContext context, Argument<? super E> argument) throws IOException {
        String id = decoder.decodeString();
        for (E constant : type.getEnumConstants()) {
            if (constant.id().equals(id)) {
                return constant;
            }
        }
        throw decoder.createDeserializationException("'" + id + "' is not one of "
                + Arrays.stream(type.getEnumConstants()).map(Wire::id).collect(Collectors.joining(", ")), id);
    }
}
