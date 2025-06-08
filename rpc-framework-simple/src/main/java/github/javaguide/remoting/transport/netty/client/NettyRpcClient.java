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
import io.netty.channel.*;
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
        /*
        * eventLoopGroup = new NioEventLoopGroup();
        * 创建了一个 EventLoopGroup（线程池），它管理一组 EventLoop 线程（默认数量为 CPU 核心数 × 2）
        * */
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
                        /*
                        * ChannelPipeline 是 Netty 中处理 I/O 事件的“处理器链”，
                        * NettyRpcClientHandler 作为入站处理器（通常继承 ChannelInboundHandlerAdapter），负责处理客户端接收到的网络数据或连接事件。
                        * 流程：
                        * 1. 服务端发送响应数据 → Netty 的 EventLoop 线程读取数据。
                        * 2. 数据经过 RpcMessageDecoder 解码（反序列化为 RpcResponse 对象）。
                        * 3. 解码后的数据传递给 NettyRpcClientHandler，触发 channelRead 方法。
                        * 4. NettyRpcClientHandler 在 channelRead 中提取 RpcResponse，
                        *    并调用 unprocessedRequests.complete(rpcResponse)（通过请求 ID 找到对应的 CompletableFuture 并完成它）。
                        * 5. CompletableFuture 完成后，RpcClientProxy.invoke 方法中的 completableFuture.get() 阻塞结束，返回响应结果给业务代码。
                        * */
                        ChannelPipeline p = ch.pipeline();
                        // 如果 5 秒内没有向服务器发送数据，则发送心跳请求
                        p.addLast(new IdleStateHandler(0, 5, 0, TimeUnit.SECONDS));
                        // 添加 RPC 消息编码器
                        p.addLast(new RpcMessageEncoder());
                        // 添加 RPC 消息解码器
                        p.addLast(new RpcMessageDecoder());
                        // 添加 Netty RPC 客户端处理器
                        /*
                        * NettyRpcClientHandler 的方法（如 channelRead、channelActive 等）不会主动执行，
                        * 而是由 Netty 的 EventLoop 线程在特定 I/O 事件发生时自动调用
                        * */
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
        // 1. 创建异步结果容器,结果是Channel类型
        /*
        * CompletableFuture 是 Java 8 引入的异步编程工具，用于表示一个“未来可能完成的异步操作结果”。
        * */
        CompletableFuture<Channel> completableFuture = new CompletableFuture<>();
        // 2. 发起异步连接
        /*
        * 当调用 bootstrap.connect(inetSocketAddress) 时：连接操作会被提交到 EventLoopGroup 中的某个 EventLoop 线程异步执行。
        * 不会新建子线程：EventLoop 线程是预先创建并复用的，负责处理所有 I/O 任务（包括连接、读写），避免了频繁创建/销毁线程的开销。
        *
        * Netty 的 bootstrap.connect(inetSocketAddress) 方法是异步非阻塞的：
        * 调用后会立即返回一个 ChannelFuture 对象（表示“未来可能完成的连接操作”），
        * 但实际的连接过程（如 TCP 三次握手）由 Netty 的 I/O 线程在后台执行。
        * 此时，主线程无法直接知道连接是否成功，需要通过回调机制（如 addListener）处理结果。
        * */
        /*
        * ChannelFuture 表示一个异步操作的结果（如连接操作）。当调用 bootstrap.connect() 时，
        * 会立即返回一个 ChannelFuture 对象（此时操作尚未完成），该对象用于跟踪操作的状态（成功/失败）。
        * 通过 addListener(ChannelFutureListener) 可以为这个 ChannelFuture 注册监听器。
        * 当异步操作完成（无论成功或失败）时，Netty 会调用监听器的 operationComplete(ChannelFuture future) 方法，
        * 并将同一个 ChannelFuture 实例作为参数传递给监听器。因此：
        * 监听器中的 future 参数，就是 bootstrap.connect() 返回的 ChannelFuture 对象。
        * */
        //步骤 1：bootstrap.connect() 返回 ChannelFuture 对象（channelFuture），此时 channelFuture.channel() 已创建但未连接。
        ChannelFuture channelFuture = bootstrap.connect(inetSocketAddress);
        channelFuture.addListener((ChannelFutureListener) future -> {
            //步骤 2：注册监听器到 channelFuture。当连接完成时，监听器的 future 参数指向 channelFuture 本身。
            if (future.isSuccess()) {
                //连接成功时：future.isSuccess() 为 true，future.channel() 返回已连接的 Channel 实例（与 channelFuture.channel() 相同）。

                log.info("The client has connected [{}] successful!", inetSocketAddress.toString());
                /*
                * 承接 Netty 连接操作的结果：通过 addListener 监听 Netty 的 ChannelFuture 结果，
                * 将连接成功的 Channel 存入 CompletableFuture。
                * */
                completableFuture.complete(future.channel());
            } else {
                // 4. 连接失败处理
                throw new IllegalStateException();
            }
        });
        // 5. 阻塞等待连接完成，返回 Channel
        /*
        * 通过 completableFuture.get() 同步阻塞等待连接完成，或通过 thenAccept 等方法异步处理结果
        * */
        return completableFuture.get();//返回已连接的 Channel 实例
    }

    @Override
    public Object sendRpcRequest(RpcRequest rpcRequest) {
        // build return value
        /*
        * CompletableFuture<RpcResponse<Object>> 实例是异步对象。
        * 它的作用是异步等待服务端响应结果，并在响应到达时通过 complete 方法传递结果。
        * */
        CompletableFuture<RpcResponse<Object>> resultFuture = new CompletableFuture<>();
        // get server address
        InetSocketAddress inetSocketAddress = serviceDiscovery.lookupService(rpcRequest);
        // get  server address related channel
        Channel channel = getChannel(inetSocketAddress);
        if (channel.isActive()) {
            // put unprocessed request
            unprocessedRequests.put(rpcRequest.getRequestId(), resultFuture);
            RpcMessage rpcMessage = RpcMessage.builder()
                    .data(rpcRequest)
                    .codec(SerializationTypeEnum.HESSIAN.getCode())
                    .compress(CompressTypeEnum.GZIP.getCode())
                    .messageType(RpcConstants.REQUEST_TYPE).build();
            //相当于先写入缓冲区再立即刷新，是发送完整消息, 在channel中发送rpc请求
            //writeAndFlush是异步的，会返回一个ChannelFuture对象，用于监听消息发送的结果
            ChannelFuture channelFuture = channel.writeAndFlush(rpcMessage);
            //监听消息是否发送成功
            //(ChannelFutureListener) future 是一个 ChannelFutureListener 接口的实例，用于监听 ChannelFuture 的事件
            channelFuture.addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    //不代表服务端已接收，仅表示本地发送完成
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
        Channel channel = channelProvider.get(inetSocketAddress); // 从 ChannelProvider 中获取已有连接
        if (channel == null) {//没有连接就新建连接
            channel = doConnect(inetSocketAddress);// 调用 doConnect 新建连接
            channelProvider.set(inetSocketAddress, channel); // 存储新连接到 ChannelProvider
        }
        return channel;
    }

    public void close() {
        eventLoopGroup.shutdownGracefully();
    }
}
