package com.moltenbits.sideband.protocol;

/** An enum whose JSON form is a fixed lowercase identifier rather than its constant name. */
interface Wire {

    String id();
}
