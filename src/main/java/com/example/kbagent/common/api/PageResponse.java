package com.example.kbagent.common.api;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * 分页结果。{@code page} 从 1 开始，与请求参数一致（ARCHITECTURE.md §6.1）。
 */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber() + 1, page.getSize(), page.getTotalElements());
    }
}
