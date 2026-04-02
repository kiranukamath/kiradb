package io.kiradb.server.command;

import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.server.storage.StorageEngine;

/**
 * Contract for all KiraDB command implementations.
 *
 * <p>Each command ({@code SET}, {@code GET}, {@code PING}, …) is a separate class
 * implementing this interface. Adding a new command means adding one class and
 * registering it in {@link io.kiradb.server.command.CommandRouter} — nothing else changes.
 *
 * <p>This is the Command Pattern: the caller ({@link CommandRouter}) knows nothing
 * about what each handler does. It just calls {@code execute()} and returns the result.
 */
@FunctionalInterface
public interface CommandHandler {

    /**
     * Execute the command and return a RESP3-encodable response.
     *
     * @param command the parsed command with name and arguments
     * @param storage the storage engine to read/write data
     * @return the response to send back to the client
     */
    Resp3Value execute(Command command, StorageEngine storage);
}
