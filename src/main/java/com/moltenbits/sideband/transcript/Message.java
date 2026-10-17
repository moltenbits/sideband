package com.moltenbits.sideband.transcript;

import io.micronaut.core.annotation.Nullable;

/**
 * One message the agent wrote to the operator.
 *
 * @param turn     the turn it was said in, as the host identifies turns; null when the reader could not tell
 * @param position the offset of the transcript line it came from
 * @param text     what was said
 * @param endsTurn the host marks it as the turn's final message rather than one said before more work
 * @param id       the identity the host gave the record, which a copy or rewrite of the transcript keeps; null when it gave none
 */
public record Message(@Nullable String turn, long position, String text, boolean endsTurn, @Nullable String id) {
}
