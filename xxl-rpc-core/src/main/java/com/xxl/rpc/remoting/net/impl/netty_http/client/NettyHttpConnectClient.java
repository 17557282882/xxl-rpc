package com.xxl.rpc.remoting.net.impl.netty_http.client;

import com.xxl.rpc.remoting.invoker.XxlRpcInvokerFactory;
import com.xxl.rpc.remoting.net.common.ConnectClient;
import com.xxl.rpc.remoting.net.params.XxlRpcRequest;
import com.xxl.rpc.serialize.Serializer;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.IdleStateHandler;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * netty_http
 *
 * @author xuxueli 2015-11-24 22:25:15
 */
public class NettyHttpConnectClient extends ConnectClient {

    private EventLoopGroup group;
    private Channel channel;

    private Serializer serializer;
    private String address;
    private String host;
    private DefaultFullHttpRequest beatRequest;

    @Override
    public void init(String address, final Serializer serializer, final XxlRpcInvokerFactory xxlRpcInvokerFactory) throws Exception {

        if (!address.toLowerCase().startsWith("http")) {
            address = "http://" + address;	// IP:PORT, need parse to url
        }

        this.address = address;
        URL url = new URL(address);
        this.host = url.getHost();
        int port = url.getPort()>-1?url.getPort():80;


        this.group = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel channel) throws Exception {
                        channel.pipeline()
                                .addLast(new IdleStateHandler(5,5,10, TimeUnit.SECONDS))
                                .addLast(new HttpClientCodec())
                                .addLast(new HttpObjectAggregator(5*1024*1024))
                                .addLast(new NettyHttpClientHandler(xxlRpcInvokerFactory, serializer, beatRequest));
                    }
                })
                .option(ChannelOption.SO_KEEPALIVE, true);
        this.channel = bootstrap.connect(host, port).sync().channel();

        this.serializer = serializer;

        // valid
        if (!isValidate()) {
            close();
            return;
        }

        logger.debug(">>>>>>>>>>> xxl-rpc netty client proxy, connect to server success at host:{}, port:{}", host, port);
    }

    private DefaultFullHttpRequest heartBeat() throws URISyntaxException {
        byte[] requestBytes = {};
        String beatPath = address.endsWith("/") ? (address+"services") : address+"/services";
        final DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                new URI(beatPath).getRawPath(), Unpooled.wrappedBuffer(requestBytes));
        request.headers().set(HttpHeaderNames.HOST, host);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, request.content().readableBytes());
        return request;
    }


    @Override
    public boolean isValidate() {
        if (this.channel != null) {
            return this.channel.isActive();
        }
        return false;
    }


    @Override
    public void close() {
        if (this.channel!=null && this.channel.isActive()) {
            this.channel.close();		// if this.channel.isOpen()
        }
        if (this.group!=null && !this.group.isShutdown()) {
            this.group.shutdownGracefully();
        }
        logger.debug(">>>>>>>>>>> xxl-rpc netty client close.");
    }


    @Override
    public void send(XxlRpcRequest xxlRpcRequest) throws Exception {
        byte[] requestBytes = serializer.serialize(xxlRpcRequest);

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, new URI(address).getRawPath(), Unpooled.wrappedBuffer(requestBytes));
        request.headers().set(HttpHeaderNames.HOST, host);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, request.content().readableBytes());

        this.channel.writeAndFlush(request).sync();
    }

}
