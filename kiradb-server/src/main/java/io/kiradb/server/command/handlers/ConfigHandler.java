package io.kiradb.server.command.handlers;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandHandler;
import io.kiradb.server.config.ConfigSubscriptionRegistry;
import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.services.config.ConfigStore;
import io.kiradb.services.config.ConfigVersion;
import io.netty.channel.Channel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single dispatcher for {@code CFG.*} RESP3 commands.
 *
 * <h2>Command summary</h2>
 * <pre>
 *   CFG.SET     scope key value          — append a new version
 *   CFG.GET     scope key                — return latest value, nil if absent
 *   CFG.HIST    scope key                — return all versions as array of maps
 *   CFG.WATCH   scope                    — subscribe this connection to change pushes
 *   CFG.UNWATCH scope                    — unsubscribe this connection
 * </pre>
 *
 * <p>Push frames are emitted by {@link ConfigSubscriptionRegistry} as RESP arrays
 * starting with the literal {@code "CFG.NOTIFY"} — clients dispatch on that
 * leading element the same way they handle Redis pub/sub messages.
 */
public final class ConfigHandler implements CommandHandler {

    private final ConfigStore store;
    private final ConfigSubscriptionRegistry registry;

    /**
     * Construct the dispatcher.
     *
     * @param store    the config store
     * @param registry the channel subscription registry (must be added as a listener
     *                 to {@code store} before this handler is used; the wiring code
     *                 in {@code KiraDBServer} handles that)
     */
    public ConfigHandler(
            final ConfigStore store, final ConfigSubscriptionRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    @Override
    public Resp3Value execute(final Command command, final StorageEngine storage) {
        // CFG.WATCH / CFG.UNWATCH need a channel — reject if invoked without one.
        // All other CFG.* are channel-independent and dispatch through the channel-aware overload.
        return executeWithoutChannel(command);
    }

    @Override
    public Resp3Value execute(
            final Command command, final StorageEngine storage, final Channel channel) {
        return switch (command.name()) {
            case "CFG.SET" -> handleSet(command);
            case "CFG.GET" -> handleGet(command);
            case "CFG.HIST" -> handleHist(command);
            case "CFG.WATCH" -> handleWatch(command, channel);
            case "CFG.UNWATCH" -> handleUnwatch(command, channel);
            default -> Resp3Value.error("ERR unknown CFG subcommand '" + command.name() + "'");
        };
    }

    /** Dispatch path that does not require a channel — used in non-Netty test paths. */
    private Resp3Value executeWithoutChannel(final Command command) {
        return switch (command.name()) {
            case "CFG.SET" -> handleSet(command);
            case "CFG.GET" -> handleGet(command);
            case "CFG.HIST" -> handleHist(command);
            case "CFG.WATCH", "CFG.UNWATCH" -> Resp3Value.error(
                    "ERR " + command.name() + " requires an active connection");
            default -> Resp3Value.error("ERR unknown CFG subcommand '" + command.name() + "'");
        };
    }

    private Resp3Value handleSet(final Command command) {
        if (command.arity() != 3) {
            return Resp3Value.wrongArity("CFG.SET");
        }
        ConfigVersion v = store.set(
                command.argAsString(0),
                command.argAsString(1),
                command.argAsString(2));
        return new Resp3Value.RespInteger(v.versionNumber());
    }

    private Resp3Value handleGet(final Command command) {
        if (command.arity() != 2) {
            return Resp3Value.wrongArity("CFG.GET");
        }
        Optional<String> v = store.get(command.argAsString(0), command.argAsString(1));
        return v.<Resp3Value>map(s -> new Resp3Value.BulkString(s.getBytes(StandardCharsets.UTF_8)))
                .orElseGet(Resp3Value::nil);
    }

    private Resp3Value handleHist(final Command command) {
        if (command.arity() != 2) {
            return Resp3Value.wrongArity("CFG.HIST");
        }
        List<ConfigVersion> hist = store.history(
                command.argAsString(0), command.argAsString(1));
        List<Resp3Value> elements = new ArrayList<>(hist.size());
        for (ConfigVersion v : hist) {
            Map<Resp3Value, Resp3Value> entries = new LinkedHashMap<>();
            entries.put(bulk("version"),   new Resp3Value.RespInteger(v.versionNumber()));
            entries.put(bulk("timestamp"), new Resp3Value.RespInteger(v.timestampMillis()));
            entries.put(bulk("value"),     bulk(v.value()));
            elements.add(new Resp3Value.RespMap(entries));
        }
        return new Resp3Value.RespArray(elements);
    }

    private Resp3Value handleWatch(final Command command, final Channel channel) {
        if (command.arity() != 1) {
            return Resp3Value.wrongArity("CFG.WATCH");
        }
        if (channel == null) {
            return Resp3Value.error("ERR CFG.WATCH requires an active connection");
        }
        registry.subscribe(command.argAsString(0), channel);
        return Resp3Value.ok();
    }

    private Resp3Value handleUnwatch(final Command command, final Channel channel) {
        if (command.arity() != 1) {
            return Resp3Value.wrongArity("CFG.UNWATCH");
        }
        if (channel == null) {
            return Resp3Value.error("ERR CFG.UNWATCH requires an active connection");
        }
        boolean wasSubscribed = registry.unsubscribe(command.argAsString(0), channel);
        return new Resp3Value.RespInteger(wasSubscribed ? 1L : 0L);
    }

    private static Resp3Value.BulkString bulk(final String s) {
        return new Resp3Value.BulkString(s.getBytes(StandardCharsets.UTF_8));
    }
}
