package io.kiradb.server.command;

import io.kiradb.server.command.handlers.DelHandler;
import io.kiradb.server.command.handlers.ExistsHandler;
import io.kiradb.server.command.handlers.ExpireHandler;
import io.kiradb.server.command.handlers.GetHandler;
import io.kiradb.server.command.handlers.PingHandler;
import io.kiradb.server.command.handlers.SetExHandler;
import io.kiradb.server.command.handlers.SetHandler;
import io.kiradb.server.command.handlers.TtlHandler;
import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.server.storage.StorageEngine;

import java.util.HashMap;
import java.util.Map;

/**
 * Routes an incoming {@link Command} to the correct {@link CommandHandler}.
 *
 * <p>The router is a simple map lookup — O(1). It does not know what any handler
 * does. Adding a new command is three lines: instantiate the handler, call
 * {@code register()}, done. No switch-case to maintain, no if-else chain.
 *
 * <p>All handlers are stateless singletons — they are created once here and
 * shared across all connections. The storage engine is passed in on each call,
 * not captured at construction time.
 */
public final class CommandRouter {

    private final Map<String, CommandHandler> handlers = new HashMap<>();
    private final StorageEngine storage;

    /**
     * Create a router wired to the given storage engine.
     *
     * @param storage the storage engine all handlers will read/write
     */
    public CommandRouter(final StorageEngine storage) {
        this.storage = storage;
        registerBuiltins();
    }

    /**
     * Route a command to its handler and return the response.
     *
     * @param command the parsed command
     * @return the RESP3 response to send to the client
     */
    public Resp3Value route(final Command command) {
        CommandHandler handler = handlers.get(command.name());
        if (handler == null) {
            return Resp3Value.error("ERR unknown command '" + command.name() + "'");
        }
        try {
            return handler.execute(command, storage);
        } catch (NumberFormatException e) {
            return Resp3Value.error("ERR value is not an integer or out of range");
        } catch (Exception e) {
            return Resp3Value.error("ERR " + e.getMessage());
        }
    }

    /**
     * Register all built-in command handlers.
     */
    private void registerBuiltins() {
        register("PING", new PingHandler());
        register("SET", new SetHandler());
        register("GET", new GetHandler());
        register("DEL", new DelHandler());
        register("EXISTS", new ExistsHandler());
        register("TTL", new TtlHandler(false));
        register("PTTL", new TtlHandler(true));
        register("EXPIRE", new ExpireHandler(false));
        register("PEXPIRE", new ExpireHandler(true));
        register("SETEX", new SetExHandler(false));
        register("PSETEX", new SetExHandler(true));
    }

    /**
     * Register a handler for a command name.
     *
     * @param name the command name (will be upper-cased)
     * @param handler the handler to invoke
     */
    public void register(final String name, final CommandHandler handler) {
        handlers.put(name.toUpperCase(), handler);
    }
}
