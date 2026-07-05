package io.kiradb.benchmark;

import io.kiradb.client.KiraDB;
import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.lsm.LsmStorageEngine;
import io.kiradb.server.KiraDBChannelHandler;
import io.kiradb.server.KiraDBServer;
import io.kiradb.server.command.CommandRouter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GET/SET throughput, measured two ways, so the cost of each layer is visible on its own:
 *
 * <ol>
 *   <li><b>Storage-engine-direct</b> ({@link EngineState}) — calls {@link LsmStorageEngine}
 *       in-process. No network, no RESP3 parsing. This is the ceiling: whatever the storage
 *       engine itself can do.</li>
 *   <li><b>Full round-trip</b> ({@link ServerState}) — a real {@link KiraDBServer} bound to a
 *       loopback port, driven by the real {@link KiraDB} SDK client over a pooled TCP
 *       connection. This is what an application actually experiences: engine cost +
 *       RESP3 encode/decode + Netty event loop + loopback TCP.</li>
 * </ol>
 *
 * <p>The gap between the two numbers is the network/protocol tax — see {@code BENCHMARKS.md}
 * for the measured gap on this machine.
 *
 * <h2>JMH configuration</h2>
 * <p>3 warmup iterations, 5 measurement iterations, 1 fork, each iteration 1 second — chosen
 * to keep a full run (both states, GET + SET) under a few minutes on a laptop. This trades
 * some measurement precision for iteration speed; see the Javadoc on {@code fork} in the
 * class-level BENCHMARKS.md discussion for what a longer, more rigorous run would use instead.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class KvThroughputBenchmark {

    /** In-process {@link LsmStorageEngine}, no network — isolates storage engine cost. */
    @State(Scope.Benchmark)
    public static class EngineState {

        private Path dataDir;
        private StorageEngine engine;
        private final AtomicLong counter = new AtomicLong();
        private byte[] presetKey;
        private byte[] presetValue;

        /**
         * Open a fresh LSM engine in a temp directory and pre-populate one key for GET.
         *
         * @throws Exception if the engine cannot be opened
         */
        @Setup(Level.Trial)
        public void setup() throws Exception {
            dataDir = Files.createTempDirectory("kiradb-bench-engine");
            engine = new LsmStorageEngine(dataDir);
            presetKey = "bench:get:key".getBytes(StandardCharsets.UTF_8);
            presetValue = "bench-value-0123456789".getBytes(StandardCharsets.UTF_8);
            engine.put(presetKey, presetValue);
        }

        /**
         * Close the engine and remove the temp directory.
         *
         * @throws Exception if cleanup fails
         */
        @TearDown(Level.Trial)
        public void tearDown() throws Exception {
            engine.close();
            deleteRecursively(dataDir);
        }
    }

    /** Real Netty server + real SDK client over loopback TCP — full round-trip cost. */
    @State(Scope.Benchmark)
    public static class ServerState {

        private static final int PORT = 17501;

        private Path dataDir;
        private StorageEngine storage;
        private Thread serverThread;
        private KiraDB client;
        private final AtomicLong counter = new AtomicLong();

        /**
         * Boot a real KiraDB server on a loopback port and connect the SDK client.
         *
         * @throws Exception if the server or client fails to start
         */
        @Setup(Level.Trial)
        public void setup() throws Exception {
            dataDir = Files.createTempDirectory("kiradb-bench-server");
            storage = new LsmStorageEngine(dataDir);
            CommandRouter router = new CommandRouter(storage);
            KiraDBChannelHandler handler = new KiraDBChannelHandler(router);

            serverThread = Thread.ofVirtual().start(() -> {
                try {
                    KiraDBServer.start(PORT, handler);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread.sleep(500);

            client = KiraDB.builder()
                    .nodes("localhost:" + PORT)
                    .connectionPool(8)
                    .build();
            client.set("bench:get:key", "bench-value-0123456789");
        }

        /**
         * Close the client, storage, and interrupt the server thread.
         */
        @TearDown(Level.Trial)
        public void tearDown() {
            if (client != null) {
                client.close();
            }
            if (storage != null) {
                storage.close();
            }
            if (serverThread != null) {
                serverThread.interrupt();
            }
            deleteRecursively(dataDir);
        }
    }

    /**
     * SET throughput against the storage engine directly, bypassing the network entirely.
     *
     * @param state    the shared engine state
     * @param blackhole JMH blackhole to prevent dead-code elimination
     */
    @Benchmark
    @Threads(4)
    public void engineSet(final EngineState state, final Blackhole blackhole) {
        long i = state.counter.getAndIncrement();
        byte[] key = ("bench:set:" + i).getBytes(StandardCharsets.UTF_8);
        state.engine.put(key, state.presetValue);
        blackhole.consume(key);
    }

    /**
     * GET throughput against the storage engine directly, bypassing the network entirely.
     *
     * @param state    the shared engine state
     * @param blackhole JMH blackhole to prevent dead-code elimination
     */
    @Benchmark
    @Threads(4)
    public void engineGet(final EngineState state, final Blackhole blackhole) {
        blackhole.consume(state.engine.get(state.presetKey));
    }

    /**
     * SET throughput through the real RESP3 wire protocol via the SDK client.
     *
     * @param state the shared server + client state
     */
    @Benchmark
    @Threads(4)
    public void serverSet(final ServerState state) {
        long i = state.counter.getAndIncrement();
        state.client.set("bench:set:" + i, "bench-value-0123456789");
    }

    /**
     * GET throughput through the real RESP3 wire protocol via the SDK client.
     *
     * @param state the shared server + client state
     */
    @Benchmark
    @Threads(4)
    public void serverGet(final ServerState state) {
        state.client.get("bench:get:key");
    }

    private static void deleteRecursively(final Path path) {
        if (path == null) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                            // best-effort cleanup
                        }
                    });
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
