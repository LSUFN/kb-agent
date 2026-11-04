package com.example.kbagent.user.domain;

/**
 * 用户的角色、区域范围、启用状态或显示名发生了变化。
 * security 模块在事务提交后据此清除该用户的状态缓存，使修改（尤其是停用）立即生效。
 */
public record UserAccountChangedEvent(long userId) {
}
