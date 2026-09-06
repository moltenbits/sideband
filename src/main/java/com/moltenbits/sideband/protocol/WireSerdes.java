package com.moltenbits.sideband.protocol;

import jakarta.inject.Singleton;

/** One serde bean per wire enum. */
final class WireSerdes {

    private WireSerdes() {
    }

    @Singleton
    static final class RoleSerde extends WireSerde<Role> {
        RoleSerde() {
            super(Role.class);
        }
    }

    @Singleton
    static final class MessageTypeSerde extends WireSerde<MessageType> {
        MessageTypeSerde() {
            super(MessageType.class);
        }
    }

    @Singleton
    static final class RouteSerde extends WireSerde<Route> {
        RouteSerde() {
            super(Route.class);
        }
    }

    @Singleton
    static final class DeliveryPolicySerde extends WireSerde<DeliveryPolicy> {
        DeliveryPolicySerde() {
            super(DeliveryPolicy.class);
        }
    }
}
