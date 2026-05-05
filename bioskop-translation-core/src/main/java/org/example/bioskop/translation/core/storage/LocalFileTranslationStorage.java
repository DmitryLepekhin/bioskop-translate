package org.example.bioskop.translation.core.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class LocalFileTranslationStorage implements TranslationStorage {
    private final Path root;

    public LocalFileTranslationStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String readText(String uri) {
        try {
            return Files.readString(resolve(uri), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TranslationStorageException("Failed to read text from " + uri, e);
        }
    }

    @Override
    public void writeText(String uri, String content) {
        Path path = resolve(uri);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TranslationStorageException("Failed to write text to " + uri, e);
        }
    }

    @Override
    public boolean exists(String uri) {
        return Files.exists(resolve(uri));
    }

    private Path resolve(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("uri must not be blank");
        }

        String path = uri;
        if (path.startsWith("file://")) {
            path = path.substring("file://".length());
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }

        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("uri escapes storage root: " + uri);
        }
        return resolved;
    }
}
