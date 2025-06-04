package github.javaguide.remoting.transport.netty.client;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * store and get Channel object
 *
 * @author shuang.kou
 * @createTime 2020年05月29日 16:36:00
 */
@Slf4j
public class ChannelProvider {
    /**
     * 用于存储套接字地址与 Channel 对象映射关系的 Map
     * 键为地址的字符串表示，值为对应的 Channel 对象
     */
    private final Map<String, Channel> channelMap;
    /**
     * 构造函数，初始化存储 Channel 对象的 Map 为线程安全的 ConcurrentHashMap
     */
    public ChannelProvider() {
        channelMap = new ConcurrentHashMap<>();
    }
    /**
     * 根据 InetSocketAddress 获取对应的 Channel 对象
     *
     * @param inetSocketAddress 目标地址
     * @return 若存在可用的 Channel 则返回该 Channel，否则返回 null
     */
    public Channel get(InetSocketAddress inetSocketAddress) {
        String key = inetSocketAddress.toString();
        // determine if there is a connection for the corresponding address
        if (channelMap.containsKey(key)) {
            Channel channel = channelMap.get(key);
            // if so, determine if the connection is available, and if so, get it directly
            if (channel != null && channel.isActive()) {
                return channel;
            } else {
                channelMap.remove(key);
            }
        }
        return null;
    }
    /**
     * 设置指定地址对应的 Channel 对象
     *
     * @param inetSocketAddress 目标地址
     * @param channel 要存储的 Channel 对象
     */
    public void set(InetSocketAddress inetSocketAddress, Channel channel) {
        String key = inetSocketAddress.toString();
        channelMap.put(key, channel);
    }
    /**
     * 移除指定地址对应的 Channel 对象
     *
     * @param inetSocketAddress 目标地址
     */
    public void remove(InetSocketAddress inetSocketAddress) {
        String key = inetSocketAddress.toString();
        channelMap.remove(key);
        log.info("Channel map size :[{}]", channelMap.size());
    }
}
