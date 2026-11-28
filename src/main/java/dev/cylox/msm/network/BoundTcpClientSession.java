package dev.cylox.msm.network;

import com.github.steveice10.packetlib.Client;
import com.github.steveice10.packetlib.tcp.TcpPacketCodec;
import com.github.steveice10.packetlib.tcp.TcpPacketEncryptor;
import com.github.steveice10.packetlib.tcp.TcpPacketSizer;
import com.github.steveice10.packetlib.tcp.TcpSession;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;

import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Hashtable;

/**
 * PacketLib 1.4 does not expose a local bind address. This adapter mirrors its
 * TCP client setup and adds Bootstrap.localAddress only for explicitly bound sessions.
 */
public final class BoundTcpClientSession extends TcpSession {
    private final Client client;
    private final InetAddress bindAddress;
    private EventLoopGroup group;

    public BoundTcpClientSession(Client client, InetAddress bindAddress) {
        super(client.getHost(), client.getPort(), client.getPacketProtocol());
        if (client == null || bindAddress == null) {
            throw new IllegalArgumentException("Client and local bind address are required.");
        }
        this.client = client;
        this.bindAddress = bindAddress;
    }

    @Override
    public void connect(boolean wait) {
        if (this.disconnected) {
            throw new IllegalStateException("Session has already been disconnected.");
        }
        if (this.group != null) return;

        try {
            final Bootstrap bootstrap = new Bootstrap();
            this.group = new NioEventLoopGroup();
            bootstrap.channel(NioSocketChannel.class)
                    .group(this.group)
                    .localAddress(new InetSocketAddress(this.bindAddress, 0))
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, getConnectTimeout() * 1000)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel channel) throws Exception {
                            getPacketProtocol().newClientSession(
                                    BoundTcpClientSession.this.client, BoundTcpClientSession.this);
                            channel.config().setOption(ChannelOption.IP_TOS, 0x18);
                            channel.config().setOption(ChannelOption.TCP_NODELAY, false);

                            ChannelPipeline pipeline = channel.pipeline();
                            refreshReadTimeoutHandler(channel);
                            refreshWriteTimeoutHandler(channel);
                            pipeline.addLast("encryption", new TcpPacketEncryptor(BoundTcpClientSession.this));
                            pipeline.addLast("sizer", new TcpPacketSizer(BoundTcpClientSession.this));
                            pipeline.addLast("codec", new TcpPacketCodec(BoundTcpClientSession.this));
                            pipeline.addLast("manager", BoundTcpClientSession.this);
                        }
                    });

            Runnable connectTask = new Runnable() {
                @Override
                public void run() {
                    try {
                        String host = getHost();
                        int port = getPort();

                        try {
                            Hashtable<String, String> environment = new Hashtable<String, String>();
                            environment.put("java.naming.factory.initial",
                                    "com.sun.jndi.dns.DnsContextFactory");
                            environment.put("java.naming.provider.url", "dns:");
                            String[] result = new InitialDirContext(environment)
                                    .getAttributes(getPacketProtocol().getSRVRecordPrefix()
                                                    + "._tcp." + host, new String[] {"SRV"})
                                    .get("srv").get().toString().split(" ", 4);
                            host = result[3];
                            port = Integer.parseInt(result[2]);
                            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
                        } catch (Throwable ignored) {
                            // Match PacketLib's normal TCP session fallback when no SRV record exists.
                        }

                        bootstrap.remoteAddress(host, port);
                        ChannelFuture future = bootstrap.connect().sync();
                        if (future.isSuccess()) {
                            while (!isConnected() && !disconnected) {
                                try {
                                    Thread.sleep(5L);
                                } catch (InterruptedException ignored) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        }
                    } catch (Throwable throwable) {
                        exceptionCaught(null, throwable);
                    }
                }
            };

            if (wait) {
                connectTask.run();
            } else {
                new Thread(connectTask, "MSM-Bound-Connection").start();
            }
        } catch (Throwable throwable) {
            exceptionCaught(null, throwable);
        }
    }

    @Override
    public void disconnect(String reason, Throwable cause, boolean wait) {
        super.disconnect(reason, cause, wait);
        EventLoopGroup activeGroup = this.group;
        this.group = null;
        if (activeGroup != null) {
            Future<?> future = activeGroup.shutdownGracefully();
            if (wait) {
                try {
                    future.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
