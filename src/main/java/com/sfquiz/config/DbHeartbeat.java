package com.sfquiz.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Pings Postgres every 60 s with a trivial SELECT 1 so the Hikari pool's
 *  minimum-idle connections never go fully idle on the dyno-to-DB socket.
 *  Heroku Postgres has been observed killing idle TCP sockets after a few
 *  minutes; without traffic, the first real request lands on a stale
 *  connection and stalls on socket timeout + reconnect (the 11-second
 *  submit-after-47-min-idle pattern in router logs).
 *
 *  Combined with Hikari's 30 s keepalive-time, this gives us two
 *  overlapping warmth sources — Hikari pings + our SELECT 1 — so a
 *  connection rarely sits unused long enough for the OS / DB to kill
 *  the socket. */
@Component
public class DbHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(DbHeartbeat.class);

    private final JdbcTemplate jdbc;

    public DbHeartbeat(DataSource ds) {
        this.jdbc = new JdbcTemplate(ds);
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 20000)
    public void pingDb() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (Exception ex) {
            // Don't spam logs — Heroku Postgres maintenance windows can
            // briefly drop the connection. Hikari will recover on the
            // next real request; we'll silently retry next tick.
            log.debug("DbHeartbeat: SELECT 1 failed: {}", ex.getMessage());
        }
    }
}
