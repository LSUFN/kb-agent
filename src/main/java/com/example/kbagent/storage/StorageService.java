package com.example.kbagent.storage;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * 文件存储（§4.1）。路径都是相对于存储根目录、以 / 分隔的字符串；目前是本地文件系统实现，
 * 以后换成 MinIO 时，上传、删除流程和对账逻辑都不需要改。I/O 失败抛出 UncheckedIOException。
 */
public interface StorageService {

    /** 临时区：上传时先写到这里。 */
    String TMP_AREA = "tmp";
    /** 正式区：数据库中的 document.storage_path 指向这里。 */
    String DOCS_AREA = "docs";

    /**
     * 把内容流式写入临时文件，同时计算 SHA-256 和大小，并保留开头几个字节用于判断文件类型。
     *
     * @throws FileTooLargeException 超过 maxBytes；此时临时文件已被删除
     */
    TempFile writeTemp(InputStream content, long maxBytes);

    /** 把临时文件原子地移动到正式位置（同一个卷，ATOMIC_MOVE）。 */
    void promote(TempFile temp, String relativePath);

    /** 删除临时文件。文件已被 promote 或已不存在时什么也不做，不抛异常。 */
    void deleteTemp(TempFile temp);

    /** 删除文件。文件不存在也视为成功（幂等）。 */
    void delete(String relativePath);

    InputStream open(String relativePath);

    boolean exists(String relativePath);

    /** 列出某个区域下最后修改时间早于 before 的文件。 */
    List<String> listOlderThan(String area, Instant before);

    /**
     * @param head 文件开头的若干字节（不足时为全部内容），用于检查文件头
     */
    record TempFile(String relativePath, String sha256, long size, byte[] head) {
    }

    class FileTooLargeException extends RuntimeException {

        public FileTooLargeException(long maxBytes) {
            super("File exceeds " + maxBytes + " bytes");
        }
    }
}
