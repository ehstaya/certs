package com.sfquiz.config;

import org.springframework.stereotype.Component;

/** Holds an in-memory boot identifier — regenerated on every restart. The
 *  client compares the value returned by {@code /api/me} against a copy
 *  stashed in localStorage; a mismatch tells the client that the server
 *  has restarted and that any persisted countdown/timer state is stale
 *  and should be wiped. */
@Component
public class ServerInfo {
    /** Millis since epoch at app construction. Stable for the lifetime of
     *  the JVM; changes on every restart. */
    private final long bootId = System.currentTimeMillis();

    public long getBootId() { return bootId; }
}
