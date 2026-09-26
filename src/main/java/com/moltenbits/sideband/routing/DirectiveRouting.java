package com.moltenbits.sideband.routing;

import com.moltenbits.sideband.protocol.InvalidEntryException;
import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.protocol.Route;
import jakarta.inject.Singleton;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/** First-token routing. A directive anywhere else in the body is ordinary content. */
@Singleton
class DirectiveRouting implements Routing {

    private static final String ALL = "@all";

    @Override
    public Destination resolve(String body, ParticipantId via, Set<ParticipantId> joined) {
        String token = firstToken(body).toLowerCase(Locale.ROOT);
        if (token.equals(ALL)) {
            SortedSet<ParticipantId> everyone = new TreeSet<>(joined);
            everyone.removeIf(ParticipantId::isHuman);
            Arrays.stream(Role.values()).map(ParticipantId::of).forEach(everyone::add);
            return directed(List.copyOf(everyone));
        }
        return instance(token).map(instance -> directed(List.of(instance)))
                .orElseGet(() -> new Destination(List.of(via), Route.DIRECT, false));
    }

    /** The client instance a token such as {@code @claude} or {@code @claude:fable} names, if it names one. */
    private static Optional<ParticipantId> instance(String token) {
        if (!token.startsWith("@")) {
            return Optional.empty();
        }
        try {
            ParticipantId named = new ParticipantId(token.substring(1));
            return named.isHuman() ? Optional.empty() : Optional.of(named);
        } catch (InvalidEntryException e) {
            return Optional.empty();
        }
    }

    private static Destination directed(List<ParticipantId> to) {
        return new Destination(to, Route.forRecipients(to), true);
    }

    private static String firstToken(String body) {
        int start = 0;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < body.length() && !Character.isWhitespace(body.charAt(end))) {
            end++;
        }
        return body.substring(start, end);
    }
}
