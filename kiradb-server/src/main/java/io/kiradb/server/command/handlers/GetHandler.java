package io.kiradb.server.command.handlers;

import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandHandler;
import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.server.storage.StorageEngine;

/**
 * GET key — retrieve the value for a key.
 *
 * <p>Returns the value as a bulk string, or null (RESP3 {@code _}) if the key
 * does not exist or has expired.
 */
public final class GetHandler implements CommandHandler {

    @Override
    public Resp3Value execute(final Command command, final StorageEngine storage) {
        if (command.arity() != 1) {
            return Resp3Value.wrongArity("GET");
        }

        return storage.get(command.argAsBytes(0))
                .map(Resp3Value.BulkString::new)
                .map(Resp3Value.class::cast)
                .orElse(Resp3Value.nil());
    }
}
