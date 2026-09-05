package com.moltenbits.sideband.protocol;

import io.micronaut.serde.annotation.Serdeable;

import java.util.Objects;

/** The delivery policy pair: one for live entries and one for backlog. */
@Serdeable
public record Delivery(DeliveryPolicy live, DeliveryPolicy backlog) {

    /** The version-one default: act on live entries, confirm backlog. */
    public static final Delivery DEFAULT = new Delivery(DeliveryPolicy.AUTO, DeliveryPolicy.CONFIRM);

    public Delivery {
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(backlog, "backlog");
    }
}
