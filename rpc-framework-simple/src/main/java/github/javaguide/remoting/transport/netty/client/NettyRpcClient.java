package github.javaguide.remoting.transport.netty.client;


import github.javaguide.enums.CompressTypeEnum;
import github.javaguide.enums.SerializationTypeEnum;
import github.javaguide.enums.ServiceDiscoveryEnum;
import github.javaguide.extension.ExtensionLoader;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.registry.ServiceDiscovery;
import github.javaguide.remoting.constants.RpcConstants;
import github.javaguide.remoting.dto.RpcMessage;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;
import github.javaguide.remoting.transport.RpcRequestTransport;
import github.javaguide.remoting.transport.netty.codec.RpcMessageDecoder;
import github.javaguide.remoting.transport.netty.codec.RpcMessageEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 初始化并关闭 Bootstrap 对象。
 * 该类负责与服务器建立连接、发送 RPC 请求以及处理响应。
 *
 * @author shuang.kou
 * @since 2020-05-29 17:51:00 // 将标签修改为 @since
 */
@Slf4j
public final class NettyRpcClient implements RpcRequestTransport {
    // 服务发现组件，用于查找服务提供者的地址
    private final ServiceDiscovery serviceDiscovery;
    // 未处理请求的容器，用于存储待处理的 RPC 请求及其对应的 CompletableFuture
    private final UnprocessedRequests unprocessedRequests;
    // 通道提供者，用于管理和获取与服务器地址对应的通道
    private final ChannelProvider channelProvider;
    // Netty 的客户端启动器，用于配置和启动客户端连接
    private final Bootstrap bootstrap;
    // 事件循环组，负责处理网络事件，如连接、读写等操作
    private final EventLoopGroup eventLoopGroup;

    /**
     * NettyRpcClient 的构造函数。
     * 初始化 EventLoopGroup、Bootstrap 等资源。
     */
    public NettyRpcClient() {
        // 初始化 EventLoopGroup，注意 NioEventLoopGroup 已被弃用
        eventLoopGroup = new NioEventLoopGroup();
        // 初始化 Bootstrap
        bootstrap = new Bootstrap();
        // 为 Bootstrap 配置 EventLoopGroup 和通道类型
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                // 添加日志处理器，日志级别为 INFO
                .handler(new LoggingHandler(LogLevel.INFO))
                // 设置连接超时时间，超时则连接失败
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                // 初始化通道管道
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // 如果 5 秒内没有向服务器发送数据，则发送心跳请求
                        p.addLast(new IdleStateHandler(0, 5, 0, TimeUnit.SECONDS));
                        // 添加 RPC 消息编码器
                        p.addLast(new RpcMessageEncoder());
                        // 添加 RPC 消息解码器
                        p.addLast(new RpcMessageDecoder());
                        // 添加 Netty RPC 客户端处理器
                        p.addLast(new NettyRpcClientHandler());
                    }
                });
        // 初始化服务发现组件
        this.serviceDiscovery = ExtensionLoader.getExtensionLoader(ServiceDiscovery.class).getExtension(ServiceDiscoveryEnum.ZK.getName());
        // 获取 UnprocessedRequests 的单例实例
        this.unprocessedRequests = SingletonFactory.getInstance(UnprocessedRequests.class);
        // 获取 ChannelProvider 的单例实例
        this.channelProvider = SingletonFactory.getInstance(ChannelProvider.class);
    }

    /**
     * connect server and get the channel ,so that you can send rpc message to server
     *
     * @param inetSocketAddress server address
     * @return the channel
     */
    @SneakyThrows
    public Channel doConnect(InetSocketAddress inetSocketAddress) {
        // 1. 创建异步结果容器
        CompletableFuture<Channel> completableFuture = new CompletableFuture<>();
        // 2. 发起异步连接
        bootstrap.connect(inetSocketAddress).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // 3. 连接成功处理
                log.info("The client has connected [{}] successful!", inetSocketAddress.toString());
                completableFuture.complete(future.channel());
            } else {
                // 4. 连接失败处理
                throw new IllegalStateException();
            }
        });
        // 5. 阻塞等待连接结果
        return completableFuture.get();
    }

    @Override
    public Object sendRpcRequest(RpcRequest rpcRequest) {
        // build return value
        CompletableFuture<RpcResponse<Object>> resultFuture = new CompletableFuture<>();
        // get server address
        InetSocketAddress inetSocketAddress = serviceDiscovery.lookupService(rpcRequest);
        // get  server address related channel
        Channel channel = getChannel(inetSocketAddress);
        if (channel.isActive()) {
            // put unprocessed request
            unprocessedRequests.put(rpcRequest.getRequestId(), resultFuture);
            RpcMessage rpcMessage = RpcMessage.builder().data(rpcRequest)
                    .codec(SerializationTypeEnum.HESSIAN.getCode())
                    .compress(CompressTypeEnum.GZIP.getCode())
                    .messageType(RpcConstants.REQUEST_TYPE).build();
            channel.writeAndFlush(rpcMessage).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    log.info("client send message: [{}]", rpcMessage);
                } else {
                    future.channel().close();
                    resultFuture.completeExceptionally(future.cause());
                    log.error("Send failed:", future.cause());
                }
            });
        } else {
            throw new IllegalStateException();
        }

        return resultFuture;
    }

    public Channel getChannel(InetSocketAddress inetSocketAddress) {
        Channel channel = channelProvider.get(inetSocketAddress);
        if (channel == null) {
            channel = doConnect(inetSocketAddress);
            channelProvider.set(inetSocketAddress, channel);
        }
        return channel;
    }

    public void close() {
        eventLoopGroup.shutdownGracefully();
    }
}
