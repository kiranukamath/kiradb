package io.kiradb.server.command.handlers;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandHandler;
import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.services.flags.FeatureFlag;
import io.kiradb.services.flags.FlagStats;
import io.kiradb.services.flags.FlagStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single dispatcher for all {@code FLAG.*} RESP3 commands. One handler is
 * registered under every flag command name; the dispatcher branches on
 * {@link Command#name()} so all commands share the same {@link FlagStore}.
 *
 * <h2>Command summary</h2>
 * <pre>
 *   FLAG.SET     name value [percent]    — value: 0|1|true|false.
 *                                           percent in [0.0, 1.0]; defaults to 1.0 if value=1, 0.0 if value=0
 *   FLAG.GET     name userId             — evaluate for user; returns 1 or 0
 *   FLAG.LIST                            — array of flag names known to this node
 *   FLAG.KILL    name                    — force OFF for everyone (rollout retained)
 *   FLAG.UNKILL  name                    — restore prior rollout
 *   FLAG.CONVERT name userId             — record a conversion attributed to user's cohort
 *   FLAG.STATS   name                    — RespMap of impressions/conversions per cohort
 * </pre>
 */
public final class FlagHandler implements CommandHandler {

    private final FlagStore flagStore;

    /**
     * Construct a flag dispatcher backed by the given store.
     *
     * @param flagStore store that owns flag state and metrics
     */
    public FlagHandler(final FlagStore flagStore) {
        this.flagStore = flagStore;
    }

    @Override
    public Resp3Value execute(final Command command, final StorageEngine storage) {
        return switch (command.name()) {
            case "FLAG.SET" -> handleSet(command);
            case "FLAG.GET" -> handleGet(command);
            case "FLAG.LIST" -> handleList(command);
            case "FLAG.KILL" -> handleKill(command);
            case "FLAG.UNKILL" -> handleUnkill(command);
            case "FLAG.CONVERT" -> handleConvert(command);
            case "FLAG.STATS" -> handleStats(command);
            default -> Resp3Value.error("ERR unknown FLAG subcommand '" + command.name() + "'");
        };
    }

    private Resp3Value handleSet(final Command command) {
        if (command.arity() < 2 || command.arity() > 3) {
            return Resp3Value.wrongArity("FLAG.SET");
        }
        String name = command.argAsString(0);
        boolean value = parseBool(command.argAsString(1));
        double rollout = command.arity() == 3
                ? Double.parseDouble(command.argAsString(2))
                : (value ? 1.0 : 0.0);
        if (rollout < 0.0 || rollout > 1.0) {
            return Resp3Value.error("ERR rollout percent must be in [0.0, 1.0]");
        }
        flagStore.set(new FeatureFlag(name, false, rollout));
        return Resp3Value.ok();
    }

    private Resp3Value handleGet(final Command command) {
        if (command.arity() != 2) {
            return Resp3Value.wrongArity("FLAG.GET");
        }
        boolean enabled = flagStore.isEnabled(
                command.argAsString(0), command.argAsString(1));
        return new Resp3Value.RespInteger(enabled ? 1L : 0L);
    }

    private Resp3Value handleList(final Command command) {
        if (command.arity() != 0) {
            return Resp3Value.wrongArity("FLAG.LIST");
        }
        List<String> names = flagStore.listFlags();
        List<Resp3Value> elements = new ArrayList<>(names.size());
        for (String n : names) {
            elements.add(new Resp3Value.BulkString(n.getBytes(StandardCharsets.UTF_8)));
        }
        return new Resp3Value.RespArray(elements);
    }

    private Resp3Value handleKill(final Command command) {
        if (command.arity() != 1) {
            return Resp3Value.wrongArity("FLAG.KILL");
        }
        boolean killed = flagStore.kill(command.argAsString(0));
        return new Resp3Value.RespInteger(killed ? 1L : 0L);
    }

    private Resp3Value handleUnkill(final Command command) {
        if (command.arity() != 1) {
            return Resp3Value.wrongArity("FLAG.UNKILL");
        }
        boolean unkilled = flagStore.unkill(command.argAsString(0));
        return new Resp3Value.RespInteger(unkilled ? 1L : 0L);
    }

    private Resp3Value handleConvert(final Command command) {
        if (command.arity() != 2) {
            return Resp3Value.wrongArity("FLAG.CONVERT");
        }
        flagStore.recordConversion(command.argAsString(0), command.argAsString(1));
        return Resp3Value.ok();
    }

    private Resp3Value handleStats(final Command command) {
        if (command.arity() != 1) {
            return Resp3Value.wrongArity("FLAG.STATS");
        }
        FlagStats s = flagStore.stats(command.argAsString(0));
        Map<Resp3Value, Resp3Value> entries = new LinkedHashMap<>();
        entries.put(bulk("enabled_impressions"), new Resp3Value.RespInteger(s.enabledImpressions()));
        entries.put(bulk("disabled_impressions"), new Resp3Value.RespInteger(s.disabledImpressions()));
        entries.put(bulk("enabled_conversions"), new Resp3Value.RespInteger(s.enabledConversions()));
        entries.put(bulk("disabled_conversions"), new Resp3Value.RespInteger(s.disabledConversions()));
        entries.put(bulk("enabled_conversion_rate"), bulk(formatRate(s.enabledConversionRate())));
        entries.put(bulk("disabled_conversion_rate"), bulk(formatRate(s.disabledConversionRate())));
        return new Resp3Value.RespMap(entries);
    }

    /** Accept "1", "0", "true", "false" (case-insensitive). */
    private static boolean parseBool(final String s) {
        return switch (s.toLowerCase()) {
            case "1", "true", "on", "enabled" -> true;
            case "0", "false", "off", "disabled" -> false;
            default -> throw new IllegalArgumentException(
                    "Expected boolean (0/1/true/false), got '" + s + "'");
        };
    }

    private static String formatRate(final double rate) {
        return rate < 0 ? "n/a" : String.format("%.4f", rate);
    }

    private static Resp3Value.BulkString bulk(final String s) {
        return new Resp3Value.BulkString(s.getBytes(StandardCharsets.UTF_8));
    }
}
