package io.kiradb.client;

import io.kiradb.client.protocol.RespValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Fluent facade over the {@code CFG.*} commands, including live watches.
 *
 * <p>{@link #watch} uses a dedicated subscriber connection shared by every
 * watch registered on this client — see {@link WatchDispatcher} for why that
 * connection lives outside the pool.
 */
public final class ConfigClient {

    private final KiraDB db;

    ConfigClient(final KiraDB db) {
        this.db = db;
    }

    /**
     * Append a new version for {@code (scope, key)}.
     *
     * @param scope the configuration scope, e.g. {@code "payment-service"}
     * @param key   the key within the scope
     * @param value the new value
     * @return the new version number
     */
    public long set(final String scope, final String key, final String value) {
        return Replies.asLong(db.call("CFG.SET", scope, key, value));
    }

    /**
     * Fetch the latest value for {@code (scope, key)}.
     *
     * @param scope the configuration scope
     * @param key   the key within the scope
     * @return the latest value, or empty if never set
     */
    public Optional<String> get(final String scope, final String key) {
        return Optional.ofNullable(Replies.asStringOrNull(db.call("CFG.GET", scope, key)));
    }

    /**
     * Fetch the full append-only version history for {@code (scope, key)}.
     *
     * @param scope the configuration scope
     * @param key   the key within the scope
     * @return all versions, oldest first
     */
    public List<ConfigEntry> history(final String scope, final String key) {
        RespValue reply = db.call("CFG.HIST", scope, key);
        if (!(reply instanceof RespValue.Array a)) {
            throw new KiraDBException("unexpected CFG.HIST reply: " + reply);
        }
        List<ConfigEntry> out = new ArrayList<>(a.elements().size());
        for (RespValue element : a.elements()) {
            if (!(element instanceof RespValue.MapValue)) {
                throw new KiraDBException("unexpected CFG.HIST element: " + element);
            }
            Map<String, RespValue> m = Replies.asMap(element);
            out.add(new ConfigEntry(
                    Replies.mapLong(m, "version"),
                    Replies.mapLong(m, "timestamp"),
                    Replies.scalarToString(m.get("value"))));
        }
        return out;
    }

    /**
     * Roll back {@code (scope, key)} to the value it held {@code versionsBack}
     * versions ago. This appends the old value as a brand-new version — history
     * is append-only, so rollback is itself an audit-trail entry, not a rewrite.
     *
     * @param scope        the configuration scope
     * @param key          the key within the scope
     * @param versionsBack how many versions back to restore from (1 = the
     *                     version immediately before the current one)
     * @return the newly appended version number, or empty if {@code versionsBack}
     *         does not address an existing version
     */
    public Optional<Long> rollback(final String scope, final String key, final int versionsBack) {
        RespValue reply = db.call("CFG.ROLLBACK", scope, key, Integer.toString(versionsBack));
        if (reply instanceof RespValue.Nil) {
            return Optional.empty();
        }
        return Optional.of(Replies.asLong(reply));
    }

    /**
     * Subscribe to live changes on a scope. The listener runs on a dedicated
     * reader thread shared by all watches on this client — keep it fast and
     * do not call back into the {@link KiraDB} client from within it.
     *
     * @param scope    the configuration scope to watch
     * @param listener invoked with each change pushed by the server
     * @return a handle to cancel this specific subscription
     */
    public WatchHandle watch(final String scope, final Consumer<ConfigChangeEvent> listener) {
        return db.watchDispatcher().watch(scope, listener);
    }
}
