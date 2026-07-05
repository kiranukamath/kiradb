package io.kiradb.server;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.lsm.LsmStorageEngine;
import io.kiradb.crdt.CrdtStore;
import io.kiradb.server.command.CommandRouter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.util.SafeEncoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the {@code CRDT.*} RESP3 commands through the real Netty
 * pipeline, using Jedis as the client. Phase 6's class-level tests prove the
 * math is right; this test proves the wire path is right.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrdtIntegrationTest {

    private static final int TEST_PORT = 16380;

    private Thread serverThread;
    private StorageEngine storage;
    private Jedis jedis;

    /** Wraps a command name as a Jedis ProtocolCommand. */
    private record Cmd(String name) implements ProtocolCommand {
        @Override
        public byte[] getRaw() {
            return name.getBytes(StandardCharsets.UTF_8);
        }
    }

    @BeforeAll
    void startServer(@TempDir Path dataDir) throws Exception {
        storage = new LsmStorageEngine(dataDir);
        CrdtStore crdtStore = new CrdtStore(storage, "test-node");
        CommandRouter router = new CommandRouter(storage, crdtStore);
        KiraDBChannelHandler handler = new KiraDBChannelHandler(router);

        serverThread = Thread.ofVirtual().start(() -> {
            try {
                KiraDBServer.start(TEST_PORT, handler);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(500);
        jedis = new Jedis("localhost", TEST_PORT);
    }

    @AfterAll
    void stopServer() throws IOException {
        if (jedis != null) {
            jedis.close();
        }
        if (storage != null) {
            storage.close();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    @Test
    void gCounterIncrAndGet() {
        Object first = jedis.sendCommand(new Cmd("CRDT.INCR"), "votes");
        assertEquals(1L, first);

        Object after5 = jedis.sendCommand(new Cmd("CRDT.INCR"), "votes", "5");
        assertEquals(6L, after5);

        Object value = jedis.sendCommand(new Cmd("CRDT.GET"), "votes");
        assertEquals(6L, value);
    }

    @Test
    void pnCounterAddSignedValues() {
        Object after100 = jedis.sendCommand(new Cmd("CRDT.PNADD"), "balance", "100");
        assertEquals(100L, after100);

        Object after30Withdraw = jedis.sendCommand(
                new Cmd("CRDT.PNADD"), "balance", "-30");
        assertEquals(70L, after30Withdraw);

        Object value = jedis.sendCommand(new Cmd("CRDT.PNGET"), "balance");
        assertEquals(70L, value);
    }

    @Test
    void lwwSetAndGet() {
        Object setReply = jedis.sendCommand(
                new Cmd("CRDT.LWWSET"), "flag.dark-mode", "true");
        assertEquals("OK", SafeEncoder.encode((byte[]) setReply));

        Object getReply = jedis.sendCommand(new Cmd("CRDT.LWWGET"), "flag.dark-mode");
        assertEquals("true", SafeEncoder.encode((byte[]) getReply));
    }

    @Test
    void lwwGetReturnsNilWhenAbsent() {
        Object reply = jedis.sendCommand(new Cmd("CRDT.LWWGET"), "no-such-flag-xyz");
        assertNull(reply);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mvRegisterRoundTrip() {
        jedis.sendCommand(new Cmd("CRDT.MVSET"), "doc.title", "First Edit");
        Object reply = jedis.sendCommand(new Cmd("CRDT.MVGET"), "doc.title");
        List<byte[]> values = (List<byte[]>) reply;
        assertEquals(1, values.size());
        assertEquals("First Edit", SafeEncoder.encode(values.get(0)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void orSetAddRemoveMembers() {
        jedis.sendCommand(new Cmd("CRDT.SADD"), "online-users", "alice", "bob");

        Object listReply = jedis.sendCommand(new Cmd("CRDT.SMEMBERS"), "online-users");
        Set<String> members = decodeStringSet((List<byte[]>) listReply);
        assertTrue(members.contains("alice"));
        assertTrue(members.contains("bob"));
        assertEquals(2, members.size());

        Object removed = jedis.sendCommand(new Cmd("CRDT.SREM"), "online-users", "alice");
        assertEquals(1L, removed);

        Object afterRemove = jedis.sendCommand(new Cmd("CRDT.SMEMBERS"), "online-users");
        Set<String> remaining = decodeStringSet((List<byte[]>) afterRemove);
        assertEquals(Set.of("bob"), remaining);
    }

    @Test
    void crdtMergeAcceptsBase64GCounterState() {
        // Local node bumps its own slot
        jedis.sendCommand(new Cmd("CRDT.INCR"), "global-events", "10");

        // Peer (different node id) bumps its slot independently and ships state.
        io.kiradb.crdt.GCounter peerCounter = new io.kiradb.crdt.GCounter("peer-node");
        peerCounter.increment(7);
        String b64 = Base64.getEncoder().encodeToString(peerCounter.serialize());

        Object mergeReply = jedis.sendCommand(
                new Cmd("CRDT.MERGE"), "GCOUNTER", "global-events", b64);
        assertEquals("OK", SafeEncoder.encode((byte[]) mergeReply));

        Object total = jedis.sendCommand(new Cmd("CRDT.GET"), "global-events");
        assertEquals(17L, total);
    }

    @Test
    void crdtMergeAcceptsBase64PnCounterState() {
        jedis.sendCommand(new Cmd("CRDT.PNADD"), "global-balance", "100");

        io.kiradb.crdt.PNCounter peer = new io.kiradb.crdt.PNCounter("peer-node");
        peer.add(50);
        peer.add(-20);
        String b64 = Base64.getEncoder().encodeToString(peer.serialize());

        Object mergeReply = jedis.sendCommand(
                new Cmd("CRDT.MERGE"), "PNCOUNTER", "global-balance", b64);
        assertEquals("OK", SafeEncoder.encode((byte[]) mergeReply));

        // Local P=100,N=0 merged with peer P=50,N=20 -> P=150,N=20 -> value=130
        Object total = jedis.sendCommand(new Cmd("CRDT.PNGET"), "global-balance");
        assertEquals(130L, total);
    }

    @Test
    void crdtMergeAcceptsBase64LwwRegisterState() {
        jedis.sendCommand(new Cmd("CRDT.LWWSET"), "global-flag", "local-value");

        // Peer write stamped far in the future so it deterministically wins the merge.
        io.kiradb.crdt.LWWRegister peer = new io.kiradb.crdt.LWWRegister("peer-node");
        peer.set("peer-value".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis() + 60_000L);
        String b64 = Base64.getEncoder().encodeToString(peer.serialize());

        Object mergeReply = jedis.sendCommand(
                new Cmd("CRDT.MERGE"), "LWWREGISTER", "global-flag", b64);
        assertEquals("OK", SafeEncoder.encode((byte[]) mergeReply));

        Object value = jedis.sendCommand(new Cmd("CRDT.LWWGET"), "global-flag");
        assertEquals("peer-value", SafeEncoder.encode((byte[]) value));
    }

    @Test
    @SuppressWarnings("unchecked")
    void crdtMergeAcceptsBase64MvRegisterState() {
        jedis.sendCommand(new Cmd("CRDT.MVSET"), "global-doc", "local-edit");

        // Concurrent peer write (peer never saw the local write) — both should survive merge.
        io.kiradb.crdt.MVRegister peer = new io.kiradb.crdt.MVRegister("peer-node");
        peer.set("peer-edit".getBytes(StandardCharsets.UTF_8));
        String b64 = Base64.getEncoder().encodeToString(peer.serialize());

        Object mergeReply = jedis.sendCommand(
                new Cmd("CRDT.MERGE"), "MVREGISTER", "global-doc", b64);
        assertEquals("OK", SafeEncoder.encode((byte[]) mergeReply));

        Object reply = jedis.sendCommand(new Cmd("CRDT.MVGET"), "global-doc");
        Set<String> values = decodeStringSet((List<byte[]>) reply);
        assertEquals(Set.of("local-edit", "peer-edit"), values);
    }

    @Test
    @SuppressWarnings("unchecked")
    void crdtMergeAcceptsBase64OrSetState() {
        jedis.sendCommand(new Cmd("CRDT.SADD"), "global-members", "alice");

        io.kiradb.crdt.ORSet peer = new io.kiradb.crdt.ORSet();
        peer.add("bob");
        String b64 = Base64.getEncoder().encodeToString(peer.serialize());

        Object mergeReply = jedis.sendCommand(
                new Cmd("CRDT.MERGE"), "ORSET", "global-members", b64);
        assertEquals("OK", SafeEncoder.encode((byte[]) mergeReply));

        Object reply = jedis.sendCommand(new Cmd("CRDT.SMEMBERS"), "global-members");
        Set<String> members = decodeStringSet((List<byte[]>) reply);
        assertEquals(Set.of("alice", "bob"), members);
    }

    @Test
    void crdtMergeRejectsUnsupportedType() {
        assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                () -> jedis.sendCommand(new Cmd("CRDT.MERGE"), "BOGUS", "x", "AA=="));
    }

    private static Set<String> decodeStringSet(final List<byte[]> raw) {
        Set<String> out = new HashSet<>();
        for (byte[] b : raw) {
            out.add(SafeEncoder.encode(b));
        }
        return out;
    }
}
