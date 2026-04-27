package io.kiradb.server.command.handlers;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.crdt.CrdtStore;
import io.kiradb.crdt.MVRegister;
import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandHandler;
import io.kiradb.server.resp3.Resp3Value;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Single dispatcher for all {@code CRDT.*} commands.
 *
 * <p>One handler is registered under every CRDT command name. The dispatcher
 * branches on {@link Command#name()} so all CRDT commands share the same
 * {@link CrdtStore} reference without duplication.
 *
 * <h2>Command summary</h2>
 * <pre>
 *   CRDT.INCR      name [delta]                  — GCounter increment
 *   CRDT.GET       name                          — GCounter value
 *   CRDT.PNADD     name delta                    — PNCounter add (signed)
 *   CRDT.PNGET     name                          — PNCounter value
 *   CRDT.LWWSET    name value                    — LWWRegister set
 *   CRDT.LWWGET    name                          — LWWRegister get
 *   CRDT.MVSET     name value                    — MVRegister write
 *   CRDT.MVGET     name                          — MVRegister read (array of concurrent values)
 *   CRDT.SADD      name member                   — ORSet add
 *   CRDT.SREM      name member                   — ORSet remove
 *   CRDT.SMEMBERS  name                          — ORSet members
 *   CRDT.MERGE     type name base64-state        — gossip merge (type ∈ {GCOUNTER})
 * </pre>
 */
public final class CrdtHandler implements CommandHandler {

    private final CrdtStore crdtStore;

    /**
     * Construct the dispatcher with the given store.
     *
     * @param crdtStore store owning all CRDT instances
     */
    public CrdtHandler(final CrdtStore crdtStore) {
        this.crdtStore = crdtStore;
    }

    @Override
    public Resp3Value execute(final Command command, final StorageEngine storage) {
        return switch (command.name()) {
            case "CRDT.INCR" -> handleIncr(command);
            case "CRDT.GET" -> handleGet(command);
            case "CRDT.PNADD" -> handlePnAdd(command);
            case "CRDT.PNGET" -> handlePnGet(command);
            case "CRDT.LWWSET" -> handleLwwSet(command);
            case "CRDT.LWWGET" -> handleLwwGet(command);
            case "CRDT.MVSET" -> handleMvSet(command);
            case "CRDT.MVGET" -> handleMvGet(command);
            case "CRDT.SADD" -> handleSAdd(command);
            case "CRDT.SREM" -> handleSRem(command);
            case "CRDT.SMEMBERS" -> handleSMembers(command);
            case "CRDT.MERGE" -> handleMerge(command);
            default -> Resp3Value.error("ERR unknown CRDT subcommand '" + command.name() + "'");
        };
    }

    private Resp3Value handleIncr(final Command command) {
        if (command.arity() < 1 || command.arity() > 2) {
            return Resp3Value.wrongArity("CRDT.INCR");
        }
        String name = command.argAsString(0);
        long delta = command.arity() == 2 ? Long.parseLong(command.argAsString(1)) : 1L;
        if (delta <= 0) {
            return Resp3Value.error("ERR CRDT.INCR delta must be positive");
        }
        long newValue = crdtStore.gCounterIncrement(name, delta);
        return new Resp3Value.RespInteger(newValue);
    }

    private Resp3Value handleGet(final Command command) {
        if (command.arity() != 1) {
            return Resp3Value.wrongArity("CRDT.GET");
        }
        return new Resp3Value.RespInteger(crdtStore.gCounterValue(command.argAsString(0)));
    }

    private Resp3Value handlePnAdd(final Command command) {
        if (command.arity() != 2) {
            return Resp3Value.wrongArity("CRDT.PNADD");
        }
        String name = command.argAsString(0);
        long delta = Long.parseLong(command.argAsString(1));
        long newValue = crdtStore.pnCounterAdd(name, delta);
        return new Resp3Value.RespInteger(newValue);
    }

    private Resp3Value handlePnGet(final Command command) {
        if (command.arity() != 1) {
            return Resp3Value.wrongArity("CRDT.PNGET");
        }
        return new Resp3Value.RespInteger(crdtStore.pnCounterValue(command.argAsString(0)));
    }

    private Resp3Value handleLwwSet(final Command command) {
        if (command.arity() != 2) {
            return Resp3Value.wrongArity("CRDT.LWWSET");
        }
        crdtStore.lwwSet(command.argAsString(0), command.argAsBytes(1));
        return Resp3Value.ok();
    }

    private Resp3Value handleLwwGet(final Command command) {
        if (command.arity() != 1) {
            return Resp3Value.wrongArity("CRDT.LWWGET");
        }
        byte[] value = crdtStore.lwwGet(command.argAsString(0));
        return value == null ? Resp3Value.nil() : new Resp3Value.BulkString(value);
    }

    private Resp3Value handleMvSet(final Command command) {
        if (command.arity() != 2) {
            return Resp3Value.wrongArity("CRDT.MVSET");
        }
        MVRegister r = crdtStore.mvRegister(command.argAsString(0));
        synchronized (r) {
            r.set(command.argAsBytes(1));
        }
        return Resp3Value.ok();
    }

    private Resp3Value handleMvGet(final Command command) {
        if (command.arity() != 1) {
            return Resp3Value.wrongArity("CRDT.MVGET");
        }
        MVRegister r = crdtStore.mvRegister(command.argAsString(0));
        List<byte[]> vals = r.values();
        List<Resp3Value> elements = new ArrayList<>(vals.size());
        for (byte[] v : vals) {
            elements.add(new Resp3Value.BulkString(v));
        }
        return new Resp3Value.RespArray(elements);
    }

    private Resp3Value handleSAdd(final Command command) {
        if (command.arity() < 2) {
            return Resp3Value.wrongArity("CRDT.SADD");
        }
        String name = command.argAsString(0);
        for (int i = 1; i < command.arity(); i++) {
            crdtStore.orSetAdd(name, command.argAsString(i));
        }
        return new Resp3Value.RespInteger(command.arity() - 1);
    }

    private Resp3Value handleSRem(final Command command) {
        if (command.arity() < 2) {
            return Resp3Value.wrongArity("CRDT.SREM");
        }
        String name = command.argAsString(0);
        long removed = 0;
        for (int i = 1; i < command.arity(); i++) {
            if (crdtStore.orSetRemove(name, command.argAsString(i))) {
                removed++;
            }
        }
        return new Resp3Value.RespInteger(removed);
    }

    private Resp3Value handleSMembers(final Command command) {
        if (command.arity() != 1) {
            return Resp3Value.wrongArity("CRDT.SMEMBERS");
        }
        Set<String> members = crdtStore.orSet(command.argAsString(0)).elements();
        List<Resp3Value> elements = new ArrayList<>(members.size());
        for (String m : members) {
            elements.add(new Resp3Value.BulkString(m.getBytes(StandardCharsets.UTF_8)));
        }
        return new Resp3Value.RespArray(elements);
    }

    private Resp3Value handleMerge(final Command command) {
        if (command.arity() != 3) {
            return Resp3Value.wrongArity("CRDT.MERGE");
        }
        String type = command.argAsString(0).toUpperCase();
        String name = command.argAsString(1);
        byte[] state;
        try {
            state = Base64.getDecoder().decode(command.argAsString(2));
        } catch (IllegalArgumentException e) {
            return Resp3Value.error("ERR CRDT.MERGE state must be valid base64");
        }
        if (!"GCOUNTER".equals(type)) {
            return Resp3Value.error("ERR unsupported CRDT.MERGE type '" + type + "'");
        }
        crdtStore.mergeGCounter(name, state);
        return Resp3Value.ok();
    }
}
