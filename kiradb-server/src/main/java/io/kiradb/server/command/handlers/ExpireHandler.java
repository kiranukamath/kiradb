package io.kiradb.server.command.handlers;

import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandHandler;
import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.core.storage.StorageEngine;

/**
 * EXPIRE key seconds — set a timeout on a key (in seconds).
 * PEXPIRE key milliseconds — same but in milliseconds.
 *
 * <p>Returns {@code 1} if the timeout was set, {@code 0} if the key does not exist.
 */
public final class ExpireHandler implements CommandHandler {

    private final boolean millis;

    /**
     * Construct an EXPIRE handler.
     *
     * @param millis true for PEXPIRE (milliseconds), false for EXPIRE (seconds)
     */
    public ExpireHandler(final boolean millis) {
        this.millis = millis;
    }

    @Override
    public Resp3Value execute(final Command command, final StorageEngine storage) {
        if (command.arity() != 2) {
            return Resp3Value.wrongArity(millis ? "PEXPIRE" : "EXPIRE");
        }

        byte[] key = command.argAsBytes(0);
        long amount = Long.parseLong(command.argAsString(1));
        long expiryMillis = System.currentTimeMillis()
                + (millis ? amount : amount * 1000);

        boolean set = storage.expire(key, expiryMillis);
        return new Resp3Value.RespInteger(set ? 1 : 0);
    }
}
