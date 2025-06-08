package github.javaguide.spring;

import github.javaguide.annotation.RpcReference;
import github.javaguide.annotation.RpcService;
import github.javaguide.config.RpcServiceConfig;
import github.javaguide.enums.RpcRequestTransportEnum;
import github.javaguide.extension.ExtensionLoader;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.provider.ServiceProvider;
import github.javaguide.provider.impl.ZkServiceProviderImpl;
import github.javaguide.proxy.RpcClientProxy;
import github.javaguide.remoting.transport.RpcRequestTransport;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;

/**
 * call this method before creating the bean to see if the class is annotated
 *
 * @author shuang.kou
 * @createTime 2020年07月14日 16:42:00
 */
@Slf4j
@Component
public class SpringBeanPostProcessor implements BeanPostProcessor {

    private final ServiceProvider serviceProvider;
    private final RpcRequestTransport rpcClient;

    public SpringBeanPostProcessor() {
        this.serviceProvider = SingletonFactory.getInstance(ZkServiceProviderImpl.class);
        this.rpcClient = ExtensionLoader.getExtensionLoader(RpcRequestTransport.class).getExtension(RpcRequestTransportEnum.NETTY.getName());
    }

    @SneakyThrows
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean.getClass().isAnnotationPresent(RpcService.class)) {
            log.info("[{}] is annotated with  [{}]", bean.getClass().getName(), RpcService.class.getCanonicalName());
            // get RpcService annotation
            RpcService rpcService = bean.getClass().getAnnotation(RpcService.class);
            // build RpcServiceProperties
            RpcServiceConfig rpcServiceConfig = RpcServiceConfig.builder()
                    .group(rpcService.group())
                    .version(rpcService.version())
                    .service(bean).build();
            serviceProvider.publishService(rpcServiceConfig);
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // 获取当前Bean的类信息（用于扫描字段）
        Class<?> targetClass = bean.getClass();
        // 获取当前Bean的所有声明字段（包括私有字段）
        Field[] declaredFields = targetClass.getDeclaredFields();
        // 遍历所有字段，查找需要注入远程服务的字段
        for (Field declaredField : declaredFields) {
            // 检查当前字段是否被 @RpcReference 注解标记（表示需要注入远程服务）
            RpcReference rpcReference = declaredField.getAnnotation(RpcReference.class);
            if (rpcReference != null) {
                // 1. 构建服务配置：从 @RpcReference 中提取分组和版本信息
                RpcServiceConfig rpcServiceConfig = RpcServiceConfig.builder()
                        .group(rpcReference.group())
                        .version(rpcReference.version()).build();
                // 2. 创建RPC客户端代理生成器：传入RPC传输组件（Netty实现）和服务配置
                /*
                * RpcClientProxy实现了InvocationHandler 接口，在里面生成代理对象和代理对象方法的调用(invoke()方法)
                * 代理对象的方法调用会被代理到InvocationHandler 接口的invoke()方法中
                *
                * rpcClient是NettyRpcClient的实例，NettyRpcClient实现了RpcRequestTransport 接口，
                * 在里面实现了sendRpcRequest()方法，该方法会发送RPC请求到服务端，包括发现服务与channel
                * */
                RpcClientProxy rpcClientProxy = new RpcClientProxy(rpcClient, rpcServiceConfig);
                // 3. 生成远程服务接口的代理对象（基于字段类型，如 HelloService）
                /*
                * 通过Proxy 类的 newProxyInstance() 创建的代理对象在调用方法的时候，
                * 实际会调用到实现InvocationHandler 接口的类的 invoke()方法
                * */
                Object clientProxy = rpcClientProxy.getProxy(declaredField.getType());
                // 4. 设置字段可访问（允许操作私有字段）
                declaredField.setAccessible(true);
                try {
                    // 5. 将代理对象注入到当前Bean的字段中（完成远程服务注入），注入到当前的客户端实体中
                    /*
                    * 即，在客户端中注入的是service的代理对象，
                    * 客户端调用service的方法时，实际是调用代理对象的方法，
                    * 代理对象的方法会被代理到InvocationHandler 接口的invoke()方法中，
                    * 然后在invoke()方法中会调用rpcClient（Netty实现）的sendRpcRequest()方法，
                    * 发送RPC请求到服务端，服务端会返回RPC响应，
                    * 然后rpcClient会将RPC响应反序列化为Java对象，
                    * 最后将Java对象返回给客户端。
                    * */
                    declaredField.set(bean, clientProxy);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }

        }
        return bean;
    }
}
