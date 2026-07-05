package io.kiradb.client.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RespReader} — one per RESP3 reply type, decoded from a
 * hand-written wire buffer (no socket involved).
 */
class RespReaderTest {

    private static RespValue parse(final String wire) throws IOException {
        return new RespReader(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8))).read();
    }

    @Test
    void simpleString() throws IOException {
        assertEquals(new RespValue.Simple("OK"), parse("+OK\r\n"));
    }

    @Test
    void error() throws IOException {
        RespValue value = parse("-ERR bad arguments\r\n");
        assertInstanceOf(RespValue.Error.class, value);
        assertEquals("ERR bad arguments", ((RespValue.Error) value).message());
    }

    @Test
    void integer() throws IOException {
        assertEquals(new RespValue.Int(42), parse(":42\r\n"));
    }

    @Test
    void negativeInteger() throws IOException {
        assertEquals(new RespValue.Int(-2), parse(":-2\r\n"));
    }

    @Test
    void bulkString() throws IOException {
        RespValue value = parse("$5\r\nhello\r\n");
        assertInstanceOf(RespValue.Bulk.class, value);
        assertEquals("hello", ((RespValue.Bulk) value).asString());
    }

    @Test
    void nilFromRespThreeNull() throws IOException {
        assertInstanceOf(RespValue.Nil.class, parse("_\r\n"));
    }

    @Test
    void nilFromResp2NullBulk() throws IOException {
        assertInstanceOf(RespValue.Nil.class, parse("$-1\r\n"));
    }

    @Test
    void nilFromResp2NullArray() throws IOException {
        assertInstanceOf(RespValue.Nil.class, parse("*-1\r\n"));
    }

    @Test
    void booleanTrueAndFalse() throws IOException {
        assertEquals(new RespValue.Bool(true), parse("#t\r\n"));
        assertEquals(new RespValue.Bool(false), parse("#f\r\n"));
    }

    @Test
    void array() throws IOException {
        RespValue value = parse("*2\r\n$3\r\nfoo\r\n:7\r\n");
        assertInstanceOf(RespValue.Array.class, value);
        List<RespValue> elements = ((RespValue.Array) value).elements();
        assertEquals(2, elements.size());
        assertEquals("foo", ((RespValue.Bulk) elements.get(0)).asString());
        assertEquals(7L, ((RespValue.Int) elements.get(1)).value());
    }

    @Test
    void nestedArray() throws IOException {
        RespValue value = parse("*1\r\n*2\r\n:1\r\n:2\r\n");
        List<RespValue> outer = ((RespValue.Array) value).elements();
        assertEquals(1, outer.size());
        List<RespValue> inner = ((RespValue.Array) outer.get(0)).elements();
        assertEquals(List.of(new RespValue.Int(1), new RespValue.Int(2)), inner);
    }

    @Test
    void map() throws IOException {
        RespValue value = parse("%2\r\n$3\r\nhit\r\n:1\r\n$4\r\nmiss\r\n:0\r\n");
        assertInstanceOf(RespValue.MapValue.class, value);
        Map<RespValue, RespValue> entries = ((RespValue.MapValue) value).entries();
        assertEquals(2, entries.size());
        assertEquals(new RespValue.Int(1), entries.get(new RespValue.Bulk("hit".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void pushFrame() throws IOException {
        RespValue value = parse(">2\r\n$10\r\nCFG.NOTIFY\r\n:5\r\n");
        assertInstanceOf(RespValue.Push.class, value);
        assertEquals(2, ((RespValue.Push) value).elements().size());
    }

    @Test
    void unknownTypeByteIsProtocolError() {
        assertThrowsIoException("?nope\r\n");
    }

    private static void assertThrowsIoException(final String wire) {
        try {
            parse(wire);
            throw new AssertionError("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("protocol error"));
        }
    }
}
