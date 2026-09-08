package com.moltenbits.sideband.protocol;

import java.util.Arrays;
import java.util.stream.Collectors;

/** An enum whose JSON and stored form is a fixed lowercase identifier rather than its constant name. */
public interface Wire {

    String id();

    /** The constant with the given identifier; an unknown identifier is an error, never a guess. */
    static <E extends Enum<E> & Wire> E fromId(Class<E> type, String id) {
        for (E constant : type.getEnumConstants()) {
            if (constant.id().equals(id)) {
                return constant;
            }
        }
        throw new InvalidEntryException("'" + id + "' is not one of "
                + Arrays.stream(type.getEnumConstants()).map(Wire::id).collect(Collectors.joining(", ")));
    }
}
