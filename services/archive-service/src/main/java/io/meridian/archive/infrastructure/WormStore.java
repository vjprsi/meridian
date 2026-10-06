package io.meridian.archive.infrastructure;

import io.meridian.common.error.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Write-once (WORM) archive object store. Objects are created exclusively and then marked read-only (the local
 * stand-in for S3 Object Lock in compliance mode); a retention sidecar records the lock-until date. A separate
 * query area holds restored copies for investigations.
 */
@Component
public class WormStore {
    public static final String SCHEME = "worm://";

    private final Path root;

    public WormStore(@Value("${meridian.archive.storage-dir:${java.io.tmpdir}/meridian-archive}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public String put(String key, byte[] content, LocalDate retainUntil) {
        Path p = resolve(key);
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.writeString(lockFile(p), retainUntil.toString(), StandardOpenOption.CREATE_NEW);
            p.toFile().setReadOnly();
            lockFile(p).toFile().setReadOnly();
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw ApiException.conflict("object_exists", "Archive object " + key + " already exists (WORM)");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write archive object " + key, e);
        }
        return SCHEME + key;
    }

    public byte[] get(String uri) {
        try {
            return Files.readAllBytes(resolve(key(uri)));
        } catch (IOException e) {
            throw new ApiException(410, "object_missing", "Archive object " + uri + " is not available");
        }
    }

    /** Object-lock state: present, read-only and locked at least until {@code expected}. */
    public record LockState(boolean exists, boolean readOnly, LocalDate lockedUntil) {
        public boolean compliant(LocalDate expected) {
            return exists && readOnly && lockedUntil != null && !lockedUntil.isBefore(expected);
        }
    }

    public LockState lockState(String uri) {
        Path p = resolve(key(uri));
        if (!Files.exists(p)) {
            return new LockState(false, false, null);
        }
        LocalDate until = null;
        try {
            if (Files.exists(lockFile(p))) {
                until = LocalDate.parse(Files.readString(lockFile(p)).trim());
            }
        } catch (IOException | java.time.DateTimeException e) {
            until = null;
        }
        return new LockState(true, !Files.isWritable(p), until);
    }

    /** Writes a restored copy to the query area; returns its location. */
    public String restore(UUID tenant, UUID objectId, byte[] content) {
        String key = tenant + "/restored/" + objectId + "/" + System.currentTimeMillis() + ".jsonl";
        Path p = resolve(key);
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, content, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot restore " + objectId, e);
        }
        return "query-area://" + key;
    }

    private static Path lockFile(Path p) {
        return p.resolveSibling(p.getFileName() + ".lock");
    }

    private static String key(String uri) {
        if (uri == null || !uri.startsWith(SCHEME)) {
            throw new ApiException(410, "object_missing", "Unknown archive location " + uri);
        }
        return uri.substring(SCHEME.length());
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw ApiException.badRequest("invalid_key", "Invalid object key");
        }
        return p;
    }
}
