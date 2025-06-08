package github.javaguide.registry.zk;

import github.javaguide.registry.ServiceRegistry;
import github.javaguide.registry.zk.util.CuratorUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;

import java.net.InetSocketAddress;

/**
 * service registration  based on zookeeper
 *
 * @author shuang.kou
 * @createTime 2020年05月31日 10:56:00
 */
@Slf4j
public class ZkServiceRegistryImpl implements ServiceRegistry {

    /**
     * 注册服务到Zookeeper
     *
     * @param rpcServiceName RPC服务的名称，用于唯一标识服务
     * @param inetSocketAddress 服务提供者的网络地址，包含IP和端口
     */
    @Override
    public void registerService(String rpcServiceName, InetSocketAddress inetSocketAddress) {
        // 构建服务在Zookeeper中的完整路径
        // CuratorUtils.ZK_REGISTER_ROOT_PATH 是Zookeeper中服务注册的根路径
        // 拼接后的路径格式为：根路径/服务名服务提供者地址
        String servicePath = CuratorUtils.ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName + inetSocketAddress.toString();
        // 获取Zookeeper客户端实例，用于与Zookeeper服务进行交互
        CuratorFramework zkClient = CuratorUtils.getZkClient();
        // 调用工具类方法，在Zookeeper中创建持久化节点
        // 持久化节点在创建后会一直存在，直到被显式删除
        CuratorUtils.createPersistentNode(zkClient, servicePath);
    }
}
