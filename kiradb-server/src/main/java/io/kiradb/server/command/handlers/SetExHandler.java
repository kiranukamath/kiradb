package io.kiradb.server.command.handlers;

import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandHandler;
import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.server.storage.StorageEngine;

/**
 * SETEX key seconds value — store a key with a seconds-based expiry.
 * PSETEX key milliseconds value — same but in milliseconds.
 *
 * <p>Jedis and redis-cli send SETEX as a distinct command rather than
 * {@code SET key value EX seconds}, so we need an explicit handler.
 * Always returns {@code +OK}.
 */
public final class SetExHandler implements CommandHandler {

    private final boolean millis;

    /**
     * Construct a SETEX handler.
     *
     * @param millis true for PSETEX (milliseconds), false for SETEX (seconds)
     */
    public SetExHandler(final boolean millis) {
        this.millis = millis;
    }

    @Override
    public Resp3Value execute(final Command command, final StorageEngine storage) {
        if (command.arity() != 3) {
            return Resp3Value.wrongArity(millis ? "PSETEX" : "SETEX");
        }

        byte[] key = command.argAsBytes(0);
        long amount = Long.parseLong(command.argAsString(1));
        byte[] value = command.argAsBytes(2);

        long expiryMillis = System.currentTimeMillis()
                + (millis ? amount : amount * 1000);

        storage.put(key, value, expiryMillis);
        return Resp3Value.ok();
    }
}
