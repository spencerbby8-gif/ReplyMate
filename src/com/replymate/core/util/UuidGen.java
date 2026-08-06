package com.replymate.core.util;

import java.util.UUID;

/** UUID-backed id generator. */
public final class UuidGen implements IdGen {
    @Override public String next() {
        return UUID.randomUUID().toString();
    }
}
