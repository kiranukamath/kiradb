package io.kiradb.server.command.handlers;

import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandHandler;
import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.server.storage.StorageEngine;

/**
 * EXISTS key [key ...] — count how many of the given keys exist.
 *
 * <p>A key mentioned multiple times is counted multiple times.
 * Returns an integer.
 */
public final class ExistsHandler implements CommandHandler {

    @Override
    public Resp3Value execute(final Command command, final StorageEngine storage) {
        if (command.arity() < 1) {
            return Resp3Value.wrongArity("EXISTS");
        }

        long count = 0;
        for (int i = 0; i < command.arity(); i++) {
            if (storage.exists(command.argAsBytes(i))) {
                count++;
            }
        }

        return new Resp3Value.RespInteger(count);
    }
}
