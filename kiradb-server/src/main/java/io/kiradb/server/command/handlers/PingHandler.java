package io.kiradb.server.command.handlers;

import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandHandler;
import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.server.storage.StorageEngine;

/**
 * PING [message] — returns PONG or echoes the message.
 *
 * <p>redis-cli uses PING as a health check. If a message is provided,
 * Redis echoes it back as a bulk string. We do the same.
 */
public final class PingHandler implements CommandHandler {

    @Override
    public Resp3Value execute(final Command command, final StorageEngine storage) {
        if (command.arity() == 0) {
            return Resp3Value.pong();
        }
        // PING "hello" → bulk string "hello"
        return new Resp3Value.BulkString(command.argAsBytes(0));
    }
}
