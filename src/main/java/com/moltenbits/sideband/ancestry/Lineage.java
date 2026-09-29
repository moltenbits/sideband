package com.moltenbits.sideband.ancestry;

import com.moltenbits.sideband.protocol.Delivery;
import com.moltenbits.sideband.protocol.DeliveryPolicy;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

/** What tracing an entry's causal links established. */
public sealed interface Lineage {

    /** The number of {@code caused_by} links version one allows before requiring confirmation. */
    int MAX_DELEGATION_DEPTH = 5;

    /** The live policy a recipient must apply, given the entry's requested delivery and its lineage. */
    DeliveryPolicy effectiveLive(Delivery requested);

    /** The rule does not apply: a human authored the entry, or it addresses no client. */
    record Exempt() implements Lineage {

        @Override
        public DeliveryPolicy effectiveLive(Delivery requested) {
            return requested.live();
        }
    }

    /**
     * The entry traces to a human-authored entry. Delivered with the entry, so a recipient held
     * to {@code confirm} can see why and read what the human asked.
     *
     * @param delegationDepth the number of {@code caused_by} links on the path that count as delegations
     * @param humanRootId     the human-authored entry the path reached
     */
    @Serdeable(naming = SnakeCaseStrategy.class)
    record Rooted(int delegationDepth, String humanRootId) implements Lineage {

        public boolean exceedsDepthCap() {
            return delegationDepth > MAX_DELEGATION_DEPTH;
        }

        @Override
        public DeliveryPolicy effectiveLive(Delivery requested) {
            return exceedsDepthCap() ? DeliveryPolicy.CONFIRM : requested.live();
        }
    }
}
