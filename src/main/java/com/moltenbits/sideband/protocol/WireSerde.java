package com.moltenbits.sideband.protocol;

import io.micronaut.core.type.Argument;
import io.micronaut.serde.Decoder;
import io.micronaut.serde.Encoder;
import io.micronaut.serde.Serde;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Reads and writes a {@link Wire} enum by its identifier; an unknown identifier is an error, never a guess. */
public abstract class WireSerde<E extends Enum<E> & Wire> implements Serde<E> {

    private final Class<E> type;

    protected WireSerde(Class<E> type) {
        this.type = type;
    }

    /** An identifier an earlier protocol revision wrote for one of these constants; none by default. */
    protected java.util.Optional<E> legacy(String id) {
        return java.util.Optional.empty();
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
        java.util.Optional<E> renamed = legacy(id);
        if (renamed.isPresent()) {
            return renamed.get();
        }
        throw decoder.createDeserializationException("'" + id + "' is not one of "
                + Arrays.stream(type.getEnumConstants()).map(Wire::id).collect(Collectors.joining(", ")), id);
    }
}
