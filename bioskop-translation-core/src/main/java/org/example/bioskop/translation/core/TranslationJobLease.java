package org.example.bioskop.translation.core;

import java.util.UUID;

public interface TranslationJobLease {
    UUID token();

    void checkpoint();
}
