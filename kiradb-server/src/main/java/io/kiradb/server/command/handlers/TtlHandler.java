package io.kiradb.server.command.handlers;

import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandHandler;
import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.server.storage.StorageEngine;

/**
 * TTL key — return the remaining time-to-live of a key in seconds.
 * PTTL key — same but in milliseconds.
 *
 * <p>Return values:
 * <ul>
 *   <li>{@code -2} — key does not exist</li>
 *   <li>{@code -1} — key has no associated expire</li>
 *   <li>{@code >= 0} — remaining TTL in seconds (TTL) or milliseconds (PTTL)</li>
 * </ul>
 */
public final class TtlHandler implements CommandHandler {

    private final boolean millis;

    /**
     * Construct a TTL handler.
     *
     * @param millis true for PTTL (milliseconds), false for TTL (seconds)
     */
    public TtlHandler(final boolean millis) {
        this.millis = millis;
    }

    @Override
    public Resp3Value execute(final Command command, final StorageEngine storage) {
        if (command.arity() != 1) {
            return Resp3Value.wrongArity(millis ? "PTTL" : "TTL");
        }

        long ttlMs = storage.ttlMillis(command.argAsBytes(0));
        if (ttlMs < 0) {
            return new Resp3Value.RespInteger(ttlMs); // -1 or -2 pass through
        }

        long result = millis ? ttlMs : (ttlMs / 1000);
        return new Resp3Value.RespInteger(result);
    }
}
