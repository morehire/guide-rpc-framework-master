package github.javaguide.remoting.transport.netty.server;

import github.javaguide.enums.CompressTypeEnum;
import github.javaguide.enums.RpcResponseCodeEnum;
import github.javaguide.enums.SerializationTypeEnum;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.remoting.constants.RpcConstants;
import github.javaguide.remoting.dto.RpcMessage;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;
import github.javaguide.remoting.handler.RpcRequestHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Customize the ChannelHandler of the server to process the data sent by the client.
 * <p>
 * 如果继承自 SimpleChannelInboundHandler 的话就不要考虑 ByteBuf 的释放 ，{@link SimpleChannelInboundHandler} 内部的
 * channelRead 方法会替你释放 ByteBuf ，避免可能导致的内存泄露问题。详见《Netty进阶之路 跟着案例学 Netty》
 *
 * @author shuang.kou
 * @createTime 2020年05月25日 20:44:00
 */
@Slf4j
public class NettyRpcServerHandler extends ChannelInboundHandlerAdapter {

    private final RpcRequestHandler rpcRequestHandler;

    public NettyRpcServerHandler() {
        this.rpcRequestHandler = SingletonFactory.getInstance(RpcRequestHandler.class);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            // 检查接收到的消息是否为RpcMessage类型
            if (msg instanceof RpcMessage) {
                log.info("server receive msg: [{}] ", msg);
                byte messageType = ((RpcMessage) msg).getMessageType();

                // 创建响应消息基础结构
                RpcMessage rpcMessage = new RpcMessage();
                // 设置序列化和压缩方式（与客户端保持一致）
                rpcMessage.setCodec(SerializationTypeEnum.HESSIAN.getCode());
                rpcMessage.setCompress(CompressTypeEnum.GZIP.getCode());

                // 心跳请求处理
                if (messageType == RpcConstants.HEARTBEAT_REQUEST_TYPE) {
                    rpcMessage.setMessageType(RpcConstants.HEARTBEAT_RESPONSE_TYPE);
                    rpcMessage.setData(RpcConstants.PONG); // 返回心跳响应
                }
                // 业务请求处理
                else {
                    // 提取RPC请求数据
                    RpcRequest rpcRequest = (RpcRequest) ((RpcMessage) msg).getData();

                    // 调用业务处理器执行目标方法
                    Object result = rpcRequestHandler.handle(rpcRequest);
                    log.info(String.format("server get result: %s", result.toString()));

                    // 设置响应类型
                    rpcMessage.setMessageType(RpcConstants.RESPONSE_TYPE);

                    // 检查通道是否可用
                    if (ctx.channel().isActive() && ctx.channel().isWritable()) {
                        // 构建成功响应（包含请求ID用于客户端匹配）
                        RpcResponse<Object> rpcResponse = RpcResponse.success(result, rpcRequest.getRequestId());
                        rpcMessage.setData(rpcResponse);
                    } else {
                        // 通道不可用时返回失败响应
                        RpcResponse<Object> rpcResponse = RpcResponse.fail(RpcResponseCodeEnum.FAIL);
                        rpcMessage.setData(rpcResponse);
                        log.error("not writable now, message dropped");
                    }
                }
                // 发送响应并添加失败关闭监听
                ctx.writeAndFlush(rpcMessage).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            }
        } finally {
            // 确保释放ByteBuf内存（防止内存泄漏）
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleState state = ((IdleStateEvent) evt).state();
            if (state == IdleState.READER_IDLE) {
                log.info("idle check happen, so close the connection");
                ctx.close();
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("server catch exception");
        cause.printStackTrace();
        ctx.close();
    }
}
