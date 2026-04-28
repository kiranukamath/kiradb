package io.kiradb.server.config;

import io.kiradb.server.resp3.Resp3Value;
import io.kiradb.services.config.ConfigChange;
import io.kiradb.services.config.ConfigChangeListener;
import io.netty.channel.Channel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Netty-aware subscription registry. Implements {@link ConfigChangeListener}
 * and translates change events into RESP3 push frames written back to every
 * channel that subscribed to the affected scope.
 *
 * <h2>Why this lives in {@code kiradb-server}, not {@code kiradb-services}</h2>
 * Netty types only exist in the server module. Keeping {@code kiradb-services}
 * Netty-free means the SDK and dashboard layers can also subscribe (via
 * {@link ConfigChangeListener}) without dragging in network types.
 *
 * <h2>Cleanup on disconnect</h2>
 * Subscribing automatically attaches a close-future listener to the channel —
 * when the client disconnects, all of its subscriptions across all scopes are
 * removed. This avoids stale entries growing forever.
 *
 * <h2>Push frame shape</h2>
 * Each notification is a RESP array:
 * <pre>
 *   ["CFG.NOTIFY", scope, key, value, version, timestampMillis]
 * </pre>
 * The leading literal {@code "CFG.NOTIFY"} matches the Redis pub/sub convention
 * (clients dispatch on the first array element) so existing SDK pub/sub paths
 * can be reused.
 */
public final class ConfigSubscriptionRegistry implements ConfigChangeListener {

    /** scope -> set of subscribed channels. */
    private final ConcurrentMap<String, Set<Channel>> subscriptions = new ConcurrentHashMap<>();

    /**
     * Subscribe a channel to change notifications for a scope. Idempotent —
     * subscribing the same channel twice is a no-op.
     *
     * @param scope   the configuration scope to subscribe to
     * @param channel the Netty channel to push notifications to
     */
    public void subscribe(final String scope, final Channel channel) {
        Set<Channel> set = subscriptions.computeIfAbsent(
                scope, s -> ConcurrentHashMap.newKeySet());
        if (set.add(channel)) {
            // Auto-cleanup when the channel closes — remove this channel from this scope.
            channel.closeFuture().addListener(future -> set.remove(channel));
        }
    }

    /**
     * Unsubscribe a channel from a scope.
     *
     * @param scope   the scope to unsubscribe from
     * @param channel the channel
     * @return true if the channel was previously subscribed
     */
    public boolean unsubscribe(final String scope, final Channel channel) {
        Set<Channel> set = subscriptions.get(scope);
        return set != null && set.remove(channel);
    }

    /**
     * @param scope the scope
     * @return number of currently subscribed channels (visible to tests)
     */
    public int subscriberCount(final String scope) {
        Set<Channel> set = subscriptions.get(scope);
        return set == null ? 0 : set.size();
    }

    @Override
    public void onChange(final ConfigChange change) {
        Set<Channel> subs = subscriptions.get(change.scope());
        if (subs == null || subs.isEmpty()) {
            return;
        }
        Resp3Value frame = pushFrame(change);
        for (Channel ch : subs) {
            if (ch.isActive()) {
                ch.writeAndFlush(frame);
            }
        }
    }

    private static Resp3Value pushFrame(final ConfigChange change) {
        List<Resp3Value> elements = new ArrayList<>(6);
        elements.add(bulk("CFG.NOTIFY"));
        elements.add(bulk(change.scope()));
        elements.add(bulk(change.key()));
        elements.add(bulk(change.newVersion().value()));
        elements.add(new Resp3Value.RespInteger(change.newVersion().versionNumber()));
        elements.add(new Resp3Value.RespInteger(change.newVersion().timestampMillis()));
        return new Resp3Value.RespArray(elements);
    }

    private static Resp3Value.BulkString bulk(final String s) {
        return new Resp3Value.BulkString(s.getBytes(StandardCharsets.UTF_8));
    }
}
