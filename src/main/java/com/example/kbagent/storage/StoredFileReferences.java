package com.example.kbagent.storage;

import java.util.Set;

/**
 * 对账时需要知道数据库引用了哪些文件。由拥有这些记录的业务模块实现（文档模块），
 * 这样 storage 模块不需要反向依赖业务模块。
 */
public interface StoredFileReferences {

    /** 数据库中引用的全部文件路径。不在其中、且已过宽限期的文件就是孤儿文件。 */
    Set<String> referencedPaths();

    /** 必须存在的文件路径（例如 READY 文档的文件），缺失时告警。 */
    Set<String> requiredPaths();
}
