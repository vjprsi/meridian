package io.meridian.reconciliation.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Stores generated files (statement files) and returns a logical {@code files://reconciliation-service/<key>} URI.
 * This adapter writes to a local directory ({@code meridian.files.dir}); production swaps in object storage.
 */
@Component
public class FileStore {
    private static final String SCHEME = "files://reconciliation-service/";
    private final Path root;

    public FileStore(Environment env) {
        String dir = env.getProperty("meridian.files.dir");
        this.root = dir != null ? Path.of(dir)
                : Path.of(System.getProperty("java.io.tmpdir"), "meridian-files", "reconciliation-service");
    }

    public String put(String key, byte[] content) {
        Path p = resolve(key);
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, content);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot store " + key, e);
        }
        return SCHEME + key;
    }

    public byte[] get(String uri) {
        try {
            return Files.readAllBytes(resolve(uri.substring(SCHEME.length())));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + uri, e);
        }
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root.normalize())) {
            throw new IllegalArgumentException("illegal file key " + key);
        }
        return p;
    }
}
