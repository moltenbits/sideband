package com.moltenbits.sideband.routing;

import com.moltenbits.sideband.protocol.ParticipantId;
import com.moltenbits.sideband.protocol.Route;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** First-token routing. A directive anywhere else in the body is ordinary content. */
@Singleton
class DirectiveRouting implements Routing {

    private static final String ALL = "@all";

    /** {@code @name} or {@code project/@name}; whether each part is a valid name is checked separately. */
    private static final Pattern DIRECTIVE = Pattern.compile("(?:([^/@]+)/)?@([^/@]+)");

    @Override
    public Destination resolve(String body, ParticipantId via, Set<ParticipantId> joined, Predicate<String> registered) {
        String project = via.project().orElseThrow(() -> new IllegalArgumentException("via must be an agent, never the operator"));
        String token = firstToken(body).toLowerCase(Locale.ROOT);
        if (token.equals(ALL)) {
            SortedSet<ParticipantId> everyone = new TreeSet<>(joined);
            everyone.removeIf(participant -> !participant.belongsTo(project));
            if (everyone.isEmpty()) {
                throw new IllegalArgumentException("@all reaches the participants joined in this project, and there are"
                        + " no joined participants in this project");
            }
            return directed(List.copyOf(everyone));
        }
        Matcher matcher = DIRECTIVE.matcher(token);
        if (!matcher.matches() || !ParticipantId.isName(matcher.group(2)) || ParticipantId.RESERVED.contains(matcher.group(2))) {
            return toVia(via, null);
        }
        String named = matcher.group(1);
        if (named == null) {
            return directed(List.of(ParticipantId.of(project, matcher.group(2))));
        }
        if (!ParticipantId.isName(named)) {
            return toVia(via, null);
        }
        if (!named.equals(project) && !registered.test(named)) {
            return toVia(via, named);
        }
        return directed(List.of(ParticipantId.of(named, matcher.group(2))));
    }

    private static Destination toVia(ParticipantId via, String unrouted) {
        return new Destination(List.of(via), Route.DIRECT, false, unrouted);
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
