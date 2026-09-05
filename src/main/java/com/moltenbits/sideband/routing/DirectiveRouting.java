package com.moltenbits.sideband.routing;

import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.protocol.Route;
import jakarta.inject.Singleton;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** First-token routing. A directive anywhere else in the body is ordinary content. */
@Singleton
class DirectiveRouting implements Routing {

    private static final String ALL = "@all";

    @Override
    public Resolution resolve(String body, Role via) {
        String token = firstToken(body).toLowerCase(Locale.ROOT);
        if (token.equals(ALL)) {
            return directed(Arrays.stream(Role.values()).map(ParticipantId::of).toList());
        }
        for (Role role : Role.values()) {
            if (token.equals("@" + role.id())) {
                return directed(List.of(ParticipantId.of(role)));
            }
        }
        return new Resolution(List.of(ParticipantId.of(via)), Route.DIRECT, false);
    }

    private static Resolution directed(List<ParticipantId> to) {
        return new Resolution(to, Route.forRecipients(to), true);
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
