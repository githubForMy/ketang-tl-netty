package cn.tuling.nettybasic.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponseDecoder;



public class ProxyHandler extends ChannelInboundHandlerAdapter {
    private final String remoteHost;
    private final int remotePort;
    private Channel outboundChannel;

    public ProxyHandler(String remoteHost, int remotePort) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        final Channel inboundChannel = ctx.channel();

        // 连接远程主机
        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
                .channel(ctx.channel().getClass())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        // 增加解码器
                        p.addLast(new HttpResponseDecoder());
                        // 增加编码器
                        p.addLast(new HttpRequestEncoder());
                        // 增加响应处理器
                        p.addLast(new ResponseHandler(inboundChannel));
                    }
                });

        ChannelFuture f = b.connect(remoteHost, remotePort);
        outboundChannel = f.channel();
        f.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    inboundChannel.read();
                } else {
                    // 连接远程主机失败，关闭入站通道
                    inboundChannel.close();
                }
            }
        });
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
        if (outboundChannel.isActive()) {
            // 将客户端请求转发到远程主机
            outboundChannel.writeAndFlush(msg).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        ctx.channel().read();
                    } else {
                        // 连接失败，关闭入站和出站通道
                        future.channel().close();
                        ctx.channel().close();
                    }
                }
            });
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (outboundChannel != null) {
            // 关闭出站通道
            closeOnFlush(outboundChannel);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }

    private static void closeOnFlush(Channel ch) {
        if (ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private class ResponseHandler extends ChannelInboundHandlerAdapter {
        private final Channel inboundChannel;

        public ResponseHandler(Channel inboundChannel) {
            this.inboundChannel = inboundChannel;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            ctx.channel().read();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            // 将远程主机的响应返回给客户端
            inboundChannel.writeAndFlush(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // 关闭入站通道
            closeOnFlush(inboundChannel);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            cause.printStackTrace();
            closeOnFlush(ctx.channel());
        }
    }
}
