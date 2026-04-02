package io.kiradb.server.command.handlers;

import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandHandler;
import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.server.storage.StorageEngine;

/**
 * SET key value [EX seconds] [PX milliseconds] — store a key-value pair.
 *
 * <p>Supported options:
 * <ul>
 *   <li>{@code EX seconds} — expire after N seconds</li>
 *   <li>{@code PX milliseconds} — expire after N milliseconds</li>
 * </ul>
 *
 * <p>Always returns {@code +OK}.
 */
public final class SetHandler implements CommandHandler {

    @Override
    public Resp3Value execute(final Command command, final StorageEngine storage) {
        if (command.arity() < 2) {
            return Resp3Value.wrongArity("SET");
        }

        byte[] key = command.argAsBytes(0);
        byte[] value = command.argAsBytes(1);

        // Parse optional EX / PX flags
        long expiryMillis = -1;
        int i = 2;
        while (i < command.arity()) {
            String flag = command.argAsString(i).toUpperCase();
            if (("EX".equals(flag) || "PX".equals(flag)) && i + 1 < command.arity()) {
                long amount = Long.parseLong(command.argAsString(i + 1));
                expiryMillis = System.currentTimeMillis()
                        + ("EX".equals(flag) ? amount * 1000 : amount);
                i += 2;
            } else {
                i++;
            }
        }

        if (expiryMillis > 0) {
            storage.put(key, value, expiryMillis);
        } else {
            storage.put(key, value);
        }

        return Resp3Value.ok();
    }
}
