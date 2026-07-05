package io.kiradb.client;

import io.kiradb.client.protocol.RespConnection;
import io.kiradb.client.protocol.RespValue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owns the dedicated subscriber connection for {@code CFG.WATCH} and fans
 * incoming {@code CFG.NOTIFY} frames out to registered listeners.
 *
 * <h2>Why a dedicated connection outside the pool</h2>
 * Once a connection subscribes, the server may write a push frame to it at any
 * moment — including between a request and its reply. Pooled request/response
 * connections would have to disentangle pushes from replies on every read and,
 * worse, a subscription would silently die when its connection is handed to
 * another borrower. Real Redis clients (Jedis, Lettuce) solve this the same
 * way: pub/sub gets its own connection with a dedicated reader. We mirror that
 * design for config watches.
 *
 * <h2>Threading</h2>
 * One virtual thread owns all reads on the socket. Frames whose first element
 * is {@code "CFG.NOTIFY"} are dispatched to listeners <em>on that reader
 * thread</em> — callbacks must be fast and must not call back into the watch
 * API. Anything else on the socket is a reply to a {@code CFG.WATCH} /
 * {@code CFG.UNWATCH} command this class itself sent, and is handed to the
 * waiting caller through a queue.
 */
final class WatchDispatcher implements AutoCloseable {

    private static final long REPLY_TIMEOUT_SECONDS = 5;

    private final RespConnection conn;
    private final Map<String, List<Consumer<ConfigChangeEvent>>> listeners =
            new ConcurrentHashMap<>();
    private final BlockingQueue<RespValue> commandReplies = new LinkedBlockingQueue<>();
    private final Thread reader;
    private volatile boolean closed;

    WatchDispatcher(final RespConnection conn) {
        this.conn = conn;
        this.reader = Thread.ofVirtual()
                .name("kiradb-watch-reader")
                .start(this::readLoop);
    }

    /**
     * Register a listener for a scope. Sends {@code CFG.WATCH} to the server
     * the first time a scope is watched.
     */
    synchronized WatchHandle watch(final String scope, final Consumer<ConfigChangeEvent> listener) {
        if (closed) {
            throw new KiraDBException("watch dispatcher is closed");
        }
        List<Consumer<ConfigChangeEvent>> scopeListeners =
                listeners.computeIfAbsent(scope, s -> new CopyOnWriteArrayList<>());
        boolean firstForScope = scopeListeners.isEmpty();
        scopeListeners.add(listener);
        if (firstForScope) {
            try {
                sendAndAwait("CFG.WATCH", scope);
            } catch (RuntimeException e) {
                scopeListeners.remove(listener);
                throw e;
            }
        }
        return new Handle(scope, listener);
    }

    @Override
    public void close() {
        closed = true;
        conn.close(); // unblocks the reader thread with an IOException
        reader.interrupt();
        listeners.clear();
    }

    private void unwatch(final String scope, final Consumer<ConfigChangeEvent> listener) {
        synchronized (this) {
            List<Consumer<ConfigChangeEvent>> scopeListeners = listeners.get(scope);
            if (scopeListeners == null || !scopeListeners.remove(listener)) {
                return; // already removed (double close)
            }
            if (scopeListeners.isEmpty() && !closed) {
                sendAndAwait("CFG.UNWATCH", scope);
            }
        }
    }

    /** Send a command on the subscriber connection and wait for its (non-push) reply. */
    private void sendAndAwait(final String... args) {
        try {
            conn.sendCommand(args);
            RespValue reply = commandReplies.poll(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (reply == null) {
                throw new KiraDBException("timed out waiting for " + args[0] + " reply");
            }
            if (reply instanceof RespValue.Error err) {
                throw new KiraDBException("server error: " + err.message());
            }
        } catch (IOException e) {
            throw new KiraDBException(args[0] + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KiraDBException("interrupted waiting for " + args[0] + " reply", e);
        }
    }

    /** Reader loop: notify frames → listeners; everything else → command reply queue. */
    private void readLoop() {
        while (!closed) {
            RespValue frame;
            try {
                frame = conn.readReply();
            } catch (IOException e) {
                // Socket closed (client shutdown) or dropped by the server.
                // Either way this dispatcher is done.
                return;
            }
            List<RespValue> elements = frameElements(frame);
            if (elements != null) {
                dispatch(elements);
            } else {
                commandReplies.offer(frame);
            }
        }
    }

    /**
     * Extract the elements of a {@code CFG.NOTIFY} frame, or null if this is
     * not one. The server sends notifications as plain arrays; a RESP3 {@code >}
     * push envelope is accepted too for forward compatibility.
     */
    private static List<RespValue> frameElements(final RespValue frame) {
        List<RespValue> elements = switch (frame) {
            case RespValue.Array a -> a.elements();
            case RespValue.Push p -> p.elements();
            default -> null;
        };
        if (elements == null || elements.isEmpty()) {
            return null;
        }
        boolean isNotify = elements.get(0) instanceof RespValue.Bulk b
                && "CFG.NOTIFY".equals(b.asString());
        return isNotify ? elements : null;
    }

    /** Decode ["CFG.NOTIFY", scope, key, value, version, timestampMillis] and fan out. */
    private void dispatch(final List<RespValue> elements) {
        if (elements.size() < 6) {
            return; // malformed frame — drop rather than kill the reader
        }
        ConfigChangeEvent event = new ConfigChangeEvent(
                Replies.scalarToString(elements.get(1)),
                Replies.scalarToString(elements.get(2)),
                Replies.scalarToString(elements.get(3)),
                Replies.asLong(elements.get(4)),
                Replies.asLong(elements.get(5)));
        List<Consumer<ConfigChangeEvent>> scopeListeners = listeners.get(event.scope());
        if (scopeListeners == null) {
            return;
        }
        for (Consumer<ConfigChangeEvent> listener : scopeListeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                // A misbehaving listener must not break dispatch to the others
                // or kill the reader thread.
            }
        }
    }

    /** Cancellation handle for one (scope, listener) registration. */
    private final class Handle implements WatchHandle {

        private final String scope;
        private final Consumer<ConfigChangeEvent> listener;

        Handle(final String scope, final Consumer<ConfigChangeEvent> listener) {
            this.scope = scope;
            this.listener = listener;
        }

        @Override
        public void close() {
            unwatch(scope, listener);
        }
    }
}
