package io.kiradb.server.resp3;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Netty {@link MessageToByteEncoder} that serializes {@link Resp3Value} objects to RESP3 bytes.
 *
 * <p>Sits at the tail of the pipeline. Command handlers produce a {@link Resp3Value};
 * this encoder writes the wire bytes into Netty's {@link ByteBuf} which is then
 * flushed to the socket.
 */
public final class Resp3Encoder extends MessageToByteEncoder<Resp3Value> {

    private static final byte[] CRLF = {'\r', '\n'};

    @Override
    protected void encode(
            final ChannelHandlerContext ctx,
            final Resp3Value msg,
            final ByteBuf out) {

        write(msg, out);
    }

    private void write(final Resp3Value value, final ByteBuf out) {
        switch (value) {
            case Resp3Value.SimpleString ss -> {
                out.writeByte('+');
                writeString(ss.value(), out);
                out.writeBytes(CRLF);
            }
            case Resp3Value.SimpleError err -> {
                out.writeByte('-');
                writeString(err.message(), out);
                out.writeBytes(CRLF);
            }
            case Resp3Value.RespInteger i -> {
                out.writeByte(':');
                writeString(Long.toString(i.value()), out);
                out.writeBytes(CRLF);
            }
            case Resp3Value.BulkString bs -> {
                out.writeByte('$');
                writeString(Integer.toString(bs.value().length), out);
                out.writeBytes(CRLF);
                out.writeBytes(bs.value());
                out.writeBytes(CRLF);
            }
            case Resp3Value.RespArray arr -> {
                out.writeByte('*');
                writeString(Integer.toString(arr.elements().size()), out);
                out.writeBytes(CRLF);
                for (Resp3Value element : arr.elements()) {
                    write(element, out);
                }
            }
            case Resp3Value.RespNull ignored -> {
                out.writeByte('_');
                out.writeBytes(CRLF);
            }
            case Resp3Value.RespBoolean bool -> {
                out.writeByte('#');
                out.writeByte(bool.value() ? 't' : 'f');
                out.writeBytes(CRLF);
            }
            case Resp3Value.RespMap map -> {
                out.writeByte('%');
                writeString(Integer.toString(map.entries().size()), out);
                out.writeBytes(CRLF);
                for (Map.Entry<Resp3Value, Resp3Value> entry : map.entries().entrySet()) {
                    write(entry.getKey(), out);
                    write(entry.getValue(), out);
                }
            }
        }
    }

    private void writeString(final String s, final ByteBuf out) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
