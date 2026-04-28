package io.kiradb.server.command;

import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.core.storage.StorageEngine;
import io.netty.channel.Channel;

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
public interface CommandHandler {

    /**
     * Execute the command and return a RESP3-encodable response.
     *
     * @param command the parsed command with name and arguments
     * @param storage the storage engine to read/write data
     * @return the response to send back to the client
     */
    Resp3Value execute(Command command, StorageEngine storage);

    /**
     * Channel-aware overload — used by handlers that need to register a
     * subscription tied to the originating connection (e.g. {@code CFG.WATCH}).
     * Default implementation ignores the channel and delegates to the simple form,
     * so existing stateless handlers don't need to override.
     *
     * @param command the parsed command
     * @param storage the storage engine
     * @param channel the Netty channel that originated this command (never null in production)
     * @return the response to send back to the client
     */
    default Resp3Value execute(
            final Command command, final StorageEngine storage, final Channel channel) {
        return execute(command, storage);
    }
}
