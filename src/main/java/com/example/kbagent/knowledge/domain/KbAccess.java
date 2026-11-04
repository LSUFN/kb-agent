package com.example.kbagent.knowledge.domain;

/**
 * 当前用户对某个知识库的有效访问级别，由角色、所有权、成员权限和可见性共同决定（KbPermissionService）。
 * 级别是累进的：MANAGE 包含 EDIT，EDIT 包含 READ。
 */
public enum KbAccess {
    NONE,
    /** 阅读、检索。 */
    READ,
    /** 另外可以上传、删除、重新处理文档。 */
    EDIT,
    /** 另外可以修改、删除知识库和管理成员（所有者、ADMIN）。 */
    MANAGE;

    public boolean includes(KbAccess required) {
        return compareTo(required) >= 0;
    }
}
