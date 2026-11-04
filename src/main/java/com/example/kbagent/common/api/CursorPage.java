package com.example.kbagent.common.api;

import java.util.List;

/**
 * 游标分页结果（用于消息这类不断追加的数据）。
 *
 * @param nextBeforeId 加载更早数据时作为 beforeId 传入；为 null 表示已经没有更早的数据
 */
public record CursorPage<T>(List<T> items, Long nextBeforeId) {
}
