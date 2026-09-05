package com.moltenbits.sideband.protocol;

import java.util.Locale;

/** How a recipient may act on an entry. */
public enum DeliveryPolicy implements Wire {

    /** Act without a further approval step. */
    AUTO,

    /** The human approves before any action. */
    CONFIRM;

    @Override
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
