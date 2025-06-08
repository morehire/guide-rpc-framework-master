package github.javaguide.remoting.transport.netty.server;

import github.javaguide.config.CustomShutdownHook;
import github.javaguide.config.RpcServiceConfig;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.provider.ServiceProvider;
import github.javaguide.provider.impl.ZkServiceProviderImpl;
import github.javaguide.remoting.transport.netty.codec.RpcMessageDecoder;
import github.javaguide.remoting.transport.netty.codec.RpcMessageEncoder;
import github.javaguide.utils.RuntimeUtil;
import github.javaguide.utils.concurrent.threadpool.ThreadPoolFactoryUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

/**
 * Server. Receive the client message, call the corresponding method according to the client message,
 * and then return the result to the client.
 *
 * @author shuang.kou
 * @createTime 2020年05月25日 16:42:00
 */
@Slf4j
@Component
public class NettyRpcServer {

    public static final int PORT = 9998;

    private final ServiceProvider serviceProvider = SingletonFactory.getInstance(ZkServiceProviderImpl.class);

    public void registerService(RpcServiceConfig rpcServiceConfig) {
        serviceProvider.publishService(rpcServiceConfig);
    }

    @SneakyThrows
    public void start() {
        // 调用自定义的关闭钩子，在 JVM 关闭前清理所有相关资源
        CustomShutdownHook.getCustomShutdownHook().clearAll();
        // 获取本机的 IP 地址，用于后续服务器绑定
        String host = InetAddress.getLocalHost().getHostAddress();
        // 创建一个 NioEventLoopGroup 作为 bossGroup，负责处理客户端的连接请求，指定线程数为 1，这是I/O 线程
        /*
        * bossGroup（主事件循环组）职责：仅负责监听客户端的连接请求（如 TCP 三次握手），
        * 并将新建立的连接（Channel）分配给 workerGroup 处理。
        * 线程数：代码中显式设置为 1（new NioEventLoopGroup(1)），
        * 因为连接请求的处理（如接受连接）是轻量级操作，一个线程足够应对大多数场景，过多线程反而会增加上下文切换开销。
        * */
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        // 创建一个 NioEventLoopGroup 作为 workerGroup，负责处理连接的读写操作
        /*
        * workerGroup（从事件循环组）职责：负责处理已连接客户端的网络 I/O 操作（如读取数据、写入数据、心跳检测等）。
        * 线程数：未显式指定（new NioEventLoopGroup()），默认线程数为 CPU 核心数 × 2（由 Netty 内部根据 Runtime.getRuntime().availableProcessors() 计算）。
        * 这是因为 I/O 操作（如数据读写）需要较高的并发能力，更多线程可以充分利用 CPU 资源，提升吞吐量。
        * */
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        //
        /*
        * 创建一个默认的事件执行器组，用于处理业务逻辑，线程数为 CPU 核心数的 2 倍，I/O 线程与业务线程分离
        * */
        DefaultEventExecutorGroup serviceHandlerGroup = new DefaultEventExecutorGroup(
                RuntimeUtil.cpus() * 2,
                // 使用自定义的线程工厂创建线程，线程名前缀为 "service-handler-group"
                ThreadPoolFactoryUtil.createThreadFactory("service-handler-group", false)
        );
        try {
            // 创建 ServerBootstrap 实例，用于配置和启动 Netty 服务器
            ServerBootstrap b = new ServerBootstrap();
            // 设置 bossGroup 和 workerGroup 到 ServerBootstrap 中
            b.group(bossGroup, workerGroup)
                    // 指定使用 NioServerSocketChannel 作为服务器通道
                    .channel(NioServerSocketChannel.class)
                    // TCP默认开启了 Nagle 算法，该算法的作用是尽可能的发送大数据快，减少网络传输。TCP_NODELAY 参数的作用就是控制是否启用 Nagle 算法。
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    // 是否开启 TCP 底层心跳机制
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    //表示系统用于临时存放已完成三次握手的请求的队列的最大长度,如果连接建立频繁，服务器处理创建新连接较慢，可以适当调大这个参数
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    // 当客户端第一次进行请求的时候才会进行初始化
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 30 秒之内没有收到客户端请求的话就关闭连接
                            ChannelPipeline p = ch.pipeline();
                            // 添加空闲状态处理器，30 秒内没有收到客户端请求则触发空闲事件
                            p.addLast(new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS));
                            // 添加 RPC 消息编码器，将 RPC 消息对象编码为字节流
                            p.addLast(new RpcMessageEncoder());
                            // 添加 RPC 消息解码器，将字节流解码为 RPC 消息对象
                            p.addLast(new RpcMessageDecoder());
                            // 使用 serviceHandlerGroup 线程池处理 NettyRpcServerHandler 的业务逻辑
                            /*
                            * NettyRpcServerHandler()是自己实现的处理器，用于处理客户端请求并返回响应
                            * */
                            p.addLast(serviceHandlerGroup, new NettyRpcServerHandler());
                        }
                    });

            // 绑定端口，同步等待绑定成功
            ChannelFuture f = b.bind(host, PORT).sync();
            // 等待服务端监听端口关闭
            f.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.error("occur exception when start server:", e);
        } finally {
            log.error("shutdown bossGroup and workerGroup");
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            serviceHandlerGroup.shutdownGracefully();
        }
    }
}
