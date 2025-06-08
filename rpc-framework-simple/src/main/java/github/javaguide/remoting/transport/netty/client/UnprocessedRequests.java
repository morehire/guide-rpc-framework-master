package github.javaguide.remoting.transport.netty.client;

import github.javaguide.remoting.dto.RpcResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * unprocessed requests by the server.
 *
 * @author shuang.kou
 * @createTime 2020年06月04日 17:30:00
 */
public class UnprocessedRequests {
    private static final Map<String, CompletableFuture<RpcResponse<Object>>> UNPROCESSED_RESPONSE_FUTURES = new ConcurrentHashMap<>();

    /**
     * 存储未处理的请求（发送请求时调用）
     * @param requestId RPC 请求的唯一标识
     * @param future 与该请求关联的异步结果对象（用于后续接收响应）
     */
    public void put(String requestId, CompletableFuture<RpcResponse<Object>> future) {
        UNPROCESSED_RESPONSE_FUTURES.put(requestId, future);
    }

    /**
     * 处理服务端响应（收到响应时调用）
     * @param rpcResponse 服务端返回的 RPC 响应对象（包含请求ID和结果）
     */
    public void complete(RpcResponse<Object> rpcResponse) {
        CompletableFuture<RpcResponse<Object>> future = UNPROCESSED_RESPONSE_FUTURES.remove(rpcResponse.getRequestId());
        if (null != future) {
            // 将服务端响应结果传递给异步对象，唤醒等待线程
            future.complete(rpcResponse);
        } else {
            throw new IllegalStateException();
        }
    }
}
