package io.kiradb.server.resp3;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Netty {@link ByteToMessageDecoder} that parses incoming bytes into {@link Resp3Value} objects.
 *
 * <p>Netty may deliver partial frames (TCP is a stream, not a message protocol).
 * {@link ByteToMessageDecoder} buffers bytes automatically and calls {@code decode()}
 * only when there is data. We use {@code resetReaderIndex()} to "put bytes back"
 * when a complete frame has not yet arrived — Netty will call us again when more
 * bytes show up.
 *
 * <p>All redis-cli commands arrive as RESP Arrays of BulkStrings, e.g.:
 * <pre>
 *   SET hello world → *3\r\n$3\r\nSET\r\n$5\r\nhello\r\n$5\r\nworld\r\n
 * </pre>
 */
public final class Resp3Decoder extends ByteToMessageDecoder {

    private static final byte CR = '\r';
    private static final byte LF = '\n';

    @Override
    protected void decode(
            final ChannelHandlerContext ctx,
            final ByteBuf in,
            final List<Object> out) {

        in.markReaderIndex();
        Resp3Value value = readValue(in);
        if (value == null) {
            // Not enough bytes yet — restore and wait for more
            in.resetReaderIndex();
            return;
        }
        out.add(value);
    }

    /**
     * Reads one complete RESP3 value from the buffer.
     * Returns {@code null} if the buffer does not yet contain a complete value.
     */
    private Resp3Value readValue(final ByteBuf buf) {
        if (!buf.isReadable()) {
            return null;
        }

        byte type = buf.readByte();
        return switch ((char) type) {
            case '+' -> readSimpleString(buf);
            case '-' -> readSimpleError(buf);
            case ':' -> readInteger(buf);
            case '$' -> readBulkString(buf);
            case '*' -> readArray(buf);
            case '_' -> readNull(buf);
            case '#' -> readBoolean(buf);
            default -> {
                // Unknown type byte — skip this connection's data
                buf.skipBytes(buf.readableBytes());
                yield Resp3Value.error("ERR protocol error: unknown type byte '" + (char) type + "'");
            }
        };
    }

    /**
     * Reads a CRLF-terminated line from the buffer as a UTF-8 string.
     * Returns {@code null} if a complete line is not yet available.
     */
    private String readLine(final ByteBuf buf) {
        int crlfIndex = indexOf(buf, buf.readerIndex());
        if (crlfIndex < 0) {
            return null;
        }
        int length = crlfIndex - buf.readerIndex();
        String line = buf.toString(buf.readerIndex(), length, StandardCharsets.UTF_8);
        buf.skipBytes(length + 2); // skip content + CRLF
        return line;
    }

    /**
     * Finds the index of \r\n in the buffer starting from {@code from}.
     */
    private int indexOf(final ByteBuf buf, final int from) {
        for (int i = from; i < buf.writerIndex() - 1; i++) {
            if (buf.getByte(i) == CR && buf.getByte(i + 1) == LF) {
                return i;
            }
        }
        return -1;
    }

    private Resp3Value readSimpleString(final ByteBuf buf) {
        String line = readLine(buf);
        if (line == null) {
            return null;
        }
        return new Resp3Value.SimpleString(line);
    }

    private Resp3Value readSimpleError(final ByteBuf buf) {
        String line = readLine(buf);
        if (line == null) {
            return null;
        }
        return new Resp3Value.SimpleError(line);
    }

    private Resp3Value readInteger(final ByteBuf buf) {
        String line = readLine(buf);
        if (line == null) {
            return null;
        }
        return new Resp3Value.RespInteger(Long.parseLong(line));
    }

    private Resp3Value readBulkString(final ByteBuf buf) {
        String lengthLine = readLine(buf);
        if (lengthLine == null) {
            return null;
        }
        int length = Integer.parseInt(lengthLine);
        if (length < 0) {
            return Resp3Value.nil(); // $-1\r\n — null bulk string (RESP2 compat)
        }
        if (buf.readableBytes() < length + 2) {
            return null; // incomplete
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        buf.skipBytes(2); // CRLF after data
        return new Resp3Value.BulkString(bytes);
    }

    private Resp3Value readArray(final ByteBuf buf) {
        String countLine = readLine(buf);
        if (countLine == null) {
            return null;
        }
        int count = Integer.parseInt(countLine);
        if (count < 0) {
            return Resp3Value.nil();
        }
        List<Resp3Value> elements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Resp3Value element = readValue(buf);
            if (element == null) {
                return null; // incomplete — caller resets reader index
            }
            elements.add(element);
        }
        return new Resp3Value.RespArray(elements);
    }

    private Resp3Value readNull(final ByteBuf buf) {
        // consume CRLF after _
        if (buf.readableBytes() < 2) {
            return null;
        }
        buf.skipBytes(2);
        return Resp3Value.nil();
    }

    private Resp3Value readBoolean(final ByteBuf buf) {
        if (buf.readableBytes() < 3) { // t\r\n or f\r\n
            return null;
        }
        byte val = buf.readByte();
        buf.skipBytes(2); // CRLF
        return new Resp3Value.RespBoolean(val == 't');
    }
}
