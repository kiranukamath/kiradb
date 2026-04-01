package io.kiradb.server.command.handlers;

import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandHandler;
import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.server.storage.StorageEngine;

/**
 * DEL key [key ...] — delete one or more keys.
 *
 * <p>Returns an integer: the number of keys that were actually deleted
 * (keys that did not exist are not counted).
 */
public final class DelHandler implements CommandHandler {

    @Override
    public Resp3Value execute(final Command command, final StorageEngine storage) {
        if (command.arity() < 1) {
            return Resp3Value.wrongArity("DEL");
        }

        long deleted = 0;
        for (int i = 0; i < command.arity(); i++) {
            if (storage.delete(command.argAsBytes(i))) {
                deleted++;
            }
        }

        return new Resp3Value.RespInteger(deleted);
    }
}
