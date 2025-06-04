package github.javaguide.remoting.dto;

import github.javaguide.enums.RpcResponseCodeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * @author shuang.kou
 * @createTime 2020年05月12日 16:15:00
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class RpcResponse<T> implements Serializable {

    private static final long serialVersionUID = 715745410605631233L;
    private String requestId;
    /**
     * response code
     */
    private Integer code;
    /**
     * response message
     */
    private String message;
    /**
     * response body
     */
    private T data;

    /**
     * 创建一个表示成功的 RpcResponse 对象
     *
     * @param <T>       响应数据的泛型类型
     * @param data      响应携带的数据，类型为泛型 T，可为 null
     * @param requestId 请求的唯一标识，用于匹配请求和响应
     * @return 包含成功响应信息的 RpcResponse 对象
     */
    public static <T> RpcResponse<T> success(T data, String requestId) {
        // 创建一个新的 RpcResponse 对象，用于封装响应信息
        RpcResponse<T> response = new RpcResponse<>();
        // 设置响应码为成功状态对应的代码
        response.setCode(RpcResponseCodeEnum.SUCCESS.getCode());
        // 设置响应消息为成功状态对应的消息
        response.setMessage(RpcResponseCodeEnum.SUCCESS.getMessage());
        // 设置请求的唯一标识，用于匹配对应的请求
        response.setRequestId(requestId);
        // 若响应数据不为 null，则将其设置到 RpcResponse 对象中
        if (null != data) {
        // 返回封装好的 RpcResponse 对象
            response.setData(data);
        }
        return response;
    }

    /**
     * 创建一个表示失败的 RpcResponse 对象
     *
     * @param <T>                  响应数据的泛型类型
     * @param rpcResponseCodeEnum  响应状态码枚举对象，包含失败的状态码和对应的消息
     * @return 包含失败响应信息的 RpcResponse 对象
     */
    public static <T> RpcResponse<T> fail(RpcResponseCodeEnum rpcResponseCodeEnum) {
        RpcResponse<T> response = new RpcResponse<>();
        response.setCode(rpcResponseCodeEnum.getCode());
        response.setMessage(rpcResponseCodeEnum.getMessage());
        return response;
    }

}
