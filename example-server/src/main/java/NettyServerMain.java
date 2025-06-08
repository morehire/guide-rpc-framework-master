import github.javaguide.HelloService;
import github.javaguide.annotation.RpcScan;
import github.javaguide.config.RpcServiceConfig;
import github.javaguide.remoting.transport.netty.server.NettyRpcServer;
import github.javaguide.serviceimpl.HelloServiceImpl2;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Server: Automatic registration service via @RpcService annotation
 *
 * @author shuang.kou
 * @createTime 2020年05月10日 07:25:00
 */
@RpcScan(basePackage = {"github.javaguide"})
public class NettyServerMain {
    public static void main(String[] args) {
        // Register service via annotation
        //HelloServiceImpl1 通过注册 @RpcService(group = "test1", version = "version1")//注解注册服务
        AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext(NettyServerMain.class);

        NettyRpcServer nettyRpcServer = (NettyRpcServer) applicationContext.getBean("nettyRpcServer");

        // Register service manually
        HelloService helloService2 = new HelloServiceImpl2();
        RpcServiceConfig rpcServiceConfig = RpcServiceConfig.builder()
                .group("test2")
                .version("version2")
                .service(helloService2)
                .build();
        nettyRpcServer.registerService(rpcServiceConfig);
        nettyRpcServer.start();//方法启动 RPC 服务器，开始监听客户端请求
    }
}
