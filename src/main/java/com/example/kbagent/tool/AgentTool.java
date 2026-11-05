package com.example.kbagent.tool;

import java.util.Set;

import com.example.kbagent.business.service.DataScope.ScopeDeniedException;
import com.example.kbagent.user.domain.Region;

/**
 * 一个业务工具。参数是强类型 record：同一个 record 既用来生成给模型的 JSON Schema，
 * 也用来做 Jakarta Validation 和范围收敛，只有一个来源。
 * <p>
 * 实现只负责业务逻辑；鉴权、校验、收敛、预算、审计和事件推送由 {@link GuardedToolCallback} 按固定顺序完成，
 * 实现不需要也不应该自己处理这些。
 *
 * @param <A> 参数类型。record 组件用 {@code @ToolParam} 描述，用 {@code @Sensitive} 标注需要脱敏的字段
 */
public interface AgentTool<A extends Record> {

    /**
     * 工具结果：返回给模型的数据，以及写入审计日志和 tool END 事件的摘要（不含明细）。
     *
     * @param result 字符串原样交给模型，其他对象序列化为 JSON
     */
    record Output(Object result, String summary) {
    }

    String name();

    String description();

    Class<A> argsType();

    /** Jakarta Validation 之后的工具特有校验，例如时间跨度。@return 不合法的原因；合法时为 null */
    default String validate(A args) {
        return null;
    }

    /**
     * 收敛数据范围（§4.4.3 第 4 步）。与区域无关的工具返回空集合。
     *
     * @throws ScopeDeniedException 请求的范围超出了用户的权限
     */
    default Set<Region> narrow(A args, AgentRequestContext ctx) {
        return Set.of();
    }

    /** 只在通过全部校验、扣减了预算之后调用。 */
    Output execute(A args, Set<Region> scope, AgentRequestContext ctx);
}
