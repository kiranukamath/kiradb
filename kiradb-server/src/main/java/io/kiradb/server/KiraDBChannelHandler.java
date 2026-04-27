package io.kiradb.server;

import io.kiradb.server.command.Command;
import io.kiradb.server.command.CommandRouter;
import io.kiradb.server.resp3.Resp3Value;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Netty inbound handler — receives decoded {@link Resp3Value} objects, converts
 * them to {@link Command}, routes to the correct handler, and writes the response.
 *
 * <p>Annotated {@link ChannelHandler.Sharable} because this handler is stateless —
 * the same instance can safely be shared across all channels (connections).
 * Netty requires this annotation to prevent accidental sharing of stateful handlers.
 *
 * <p>Pipeline position:
 * <pre>
 *   [socket bytes] → Resp3Decoder → KiraDBChannelHandler → Resp3Encoder → [socket bytes]
 * </pre>
 */
@ChannelHandler.Sharable
public final class KiraDBChannelHandler extends SimpleChannelInboundHandler<Resp3Value> {

    private static final Logger LOG = LoggerFactory.getLogger(KiraDBChannelHandler.class);

    private final CommandRouter router;

    /**
     * Construct the channel handler with a pre-built router.
     *
     * @param router the command router to dispatch incoming commands to
     */
    public KiraDBChannelHandler(final CommandRouter router) {
        this.router = router;
    }

    @Override
    protected void channelRead0(
            final ChannelHandlerContext ctx,
            final Resp3Value msg) {

        Command command = toCommand(msg);
        if (command == null) {
            ctx.writeAndFlush(Resp3Value.error("ERR protocol error: expected array"));
            return;
        }

        LOG.debug("Command: {} {}", command.name(), command.args());
        Resp3Value response = router.route(command, ctx.channel());
        ctx.writeAndFlush(response);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        LOG.error("Channel error — closing connection", cause);
        ctx.writeAndFlush(Resp3Value.error("ERR internal server error"));
        ctx.close();
    }

    /**
     * Convert a decoded RESP3 value (always an array from redis-cli) into a Command.
     * Returns null if the value is not a valid command array.
     */
    private Command toCommand(final Resp3Value value) {
        if (!(value instanceof Resp3Value.RespArray array)) {
            return null;
        }
        List<Resp3Value> elements = array.elements();
        if (elements.isEmpty()) {
            return null;
        }

        // First element is the command name
        Resp3Value first = elements.get(0);
        if (!(first instanceof Resp3Value.BulkString nameBytes)) {
            return null;
        }

        String name = new String(nameBytes.value(), StandardCharsets.UTF_8).toUpperCase();

        // Remaining elements are arguments
        List<byte[]> args = elements.subList(1, elements.size())
                .stream()
                .map(el -> switch (el) {
                    case Resp3Value.BulkString bs -> bs.value();
                    case Resp3Value.SimpleString ss -> ss.value().getBytes(StandardCharsets.UTF_8);
                    default -> new byte[0];
                })
                .toList();

        return new Command(name, args);
    }
}
