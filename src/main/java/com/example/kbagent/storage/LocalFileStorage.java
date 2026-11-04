package com.example.kbagent.storage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地文件系统实现。临时区和正式区在同一个根目录下，保证 promote 可以使用原子移动。
 */
@Component
@EnableConfigurationProperties(StorageProperties.class)
public class LocalFileStorage implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);
    private static final int HEAD_BYTES = 8;

    private final Path root;

    public LocalFileStorage(StorageProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root.resolve(TMP_AREA));
            Files.createDirectories(root.resolve(DOCS_AREA));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage directories under " + root, e);
        }
    }

    @Override
    public TempFile writeTemp(InputStream content, long maxBytes) {
        String relativePath = TMP_AREA + "/" + UUID.randomUUID() + ".part";
        Path target = resolve(relativePath);
        MessageDigest sha256 = newSha256();
        byte[] head = new byte[HEAD_BYTES];
        long size = 0;
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = content.read(buffer)) != -1) {
                if (size < HEAD_BYTES) {
                    System.arraycopy(buffer, 0, head, (int) size, (int) Math.min(read, HEAD_BYTES - size));
                }
                size += read;
                if (size > maxBytes) {
                    throw new FileTooLargeException(maxBytes);
                }
                sha256.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            deleteQuietly(target);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
        return new TempFile(relativePath, HexFormat.of().formatHex(sha256.digest()), size,
                Arrays.copyOf(head, (int) Math.min(size, HEAD_BYTES)));
    }

    @Override
    public void promote(TempFile temp, String relativePath) {
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.move(resolve(temp.relativePath()), target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void deleteTemp(TempFile temp) {
        deleteQuietly(resolve(temp.relativePath()));
    }

    @Override
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InputStream open(String relativePath) {
        try {
            return Files.newInputStream(resolve(relativePath));
        } catch (NoSuchFileException e) {
            throw new UncheckedIOException("File not found: " + relativePath, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean exists(String relativePath) {
        return Files.isRegularFile(resolve(relativePath));
    }

    @Override
    public List<String> listOlderThan(String area, Instant before) {
        Path areaRoot = resolve(area);
        if (!Files.isDirectory(areaRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(areaRoot)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> lastModified(file).isBefore(before))
                    .map(file -> root.relativize(file).toString().replace(File.separatorChar, '/'))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 解析相对路径，并拒绝跳出根目录的路径（例如 ../）。 */
    private Path resolve(String relativePath) {
        Path path = root.resolve(relativePath).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Illegal storage path: " + relativePath);
        }
        return path;
    }

    private static Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            // 文件在遍历过程中被删除：视为“很新”，本轮不处理
            return Instant.MAX;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("failed to delete {}; it will be removed by the reconciler", path, e);
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
