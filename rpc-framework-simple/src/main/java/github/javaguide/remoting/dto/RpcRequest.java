package github.javaguide.remoting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;

/**
 * RPC 请求类，用于封装 RPC 调用所需的信息
 * @author shuang.kou
 * @createTime 2020年05月10日 08:24:00
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@ToString
public class RpcRequest implements Serializable {
    // 序列化版本号，用于保证序列化和反序列化的兼容性
    private static final long serialVersionUID = 1905122041950251207L;
    // 请求的唯一标识，用于匹配请求和响应
    private String requestId;
    // 要调用的接口名称
    private String interfaceName;
    // 要调用的方法名称
    private String methodName;
    // 调用方法时传递的参数数组
    private Object[] parameters;
    // 调用方法时传递参数的类型数组
    private Class<?>[] paramTypes;
    // 服务的版本号
    private String version;
    // 服务的分组信息
    private String group;
    /**
     * 获取 RPC 服务的完整名称，由接口名称、分组信息和版本号组合而成
     * @return RPC 服务的完整名称
     */
    public String getRpcServiceName() {
        return this.getInterfaceName() + this.getGroup() + this.getVersion();
    }
}
