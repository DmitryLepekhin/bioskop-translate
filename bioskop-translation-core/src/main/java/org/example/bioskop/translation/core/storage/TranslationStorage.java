package org.example.bioskop.translation.core.storage;

public interface TranslationStorage {
    String readText(String uri);

    void writeText(String uri, String content);

    boolean exists(String uri);
}
