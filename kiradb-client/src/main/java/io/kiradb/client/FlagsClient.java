package io.kiradb.client;

import io.kiradb.client.protocol.RespValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fluent facade over the {@code FLAG.*} commands.
 *
 * <p>Stateless and cheap — {@link KiraDB#flags()} returns a new instance per
 * call; hold on to one or call it fresh each time, it makes no difference.
 */
public final class FlagsClient {

    private final KiraDB db;

    FlagsClient(final KiraDB db) {
        this.db = db;
    }

    /**
     * Evaluate a flag for a user (also counts as an impression on the server).
     *
     * @param name   the flag name
     * @param userId the user identifier used for sticky bucketing
     * @return true if the flag is enabled for this user
     */
    public boolean isEnabled(final String name, final String userId) {
        return Replies.asLong(db.call("FLAG.GET", name, userId)) == 1L;
    }

    /**
     * Create or update a flag's rollout.
     *
     * @param name           the flag name
     * @param enabled        base on/off state used to derive a default rollout
     *                       when {@code rolloutPercent} is not given explicitly
     * @param rolloutPercent fraction of users bucketed into the enabled cohort, in {@code [0.0, 1.0]}
     */
    public void set(final String name, final boolean enabled, final double rolloutPercent) {
        db.call("FLAG.SET", name, enabled ? "true" : "false", Double.toString(rolloutPercent));
    }

    /**
     * Force a flag off for everyone. The rollout percentage is retained so
     * {@link #unkill} restores it.
     *
     * @param name the flag name
     * @return true if the flag existed and was killed
     */
    public boolean kill(final String name) {
        return Replies.asLong(db.call("FLAG.KILL", name)) == 1L;
    }

    /**
     * Restore a flag's prior rollout after {@link #kill}.
     *
     * @param name the flag name
     * @return true if the flag existed and was unkilled
     */
    public boolean unkill(final String name) {
        return Replies.asLong(db.call("FLAG.UNKILL", name)) == 1L;
    }

    /**
     * List all flag names known to the node.
     *
     * @return the flag names
     */
    public List<String> list() {
        RespValue reply = db.call("FLAG.LIST");
        if (!(reply instanceof RespValue.Array a)) {
            throw new KiraDBException("unexpected FLAG.LIST reply: " + reply);
        }
        List<String> names = new ArrayList<>(a.elements().size());
        for (RespValue e : a.elements()) {
            names.add(Replies.scalarToString(e));
        }
        return names;
    }

    /**
     * Record a conversion attributed to the user's current cohort.
     *
     * @param name   the flag name
     * @param userId the user identifier (must match the id used at evaluation time)
     */
    public void convert(final String name, final String userId) {
        db.call("FLAG.CONVERT", name, userId);
    }

    /**
     * Fetch impression/conversion metrics for a flag.
     *
     * @param name the flag name
     * @return per-cohort metrics
     */
    public FlagStats stats(final String name) {
        Map<String, RespValue> m = Replies.asMap(db.call("FLAG.STATS", name));
        return new FlagStats(
                Replies.mapLong(m, "enabled_impressions"),
                Replies.mapLong(m, "disabled_impressions"),
                Replies.mapLong(m, "enabled_conversions"),
                Replies.mapLong(m, "disabled_conversions"),
                Replies.mapRate(m, "enabled_conversion_rate"),
                Replies.mapRate(m, "disabled_conversion_rate"));
    }
}
