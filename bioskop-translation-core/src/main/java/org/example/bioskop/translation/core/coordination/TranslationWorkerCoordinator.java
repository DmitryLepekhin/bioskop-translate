package org.example.bioskop.translation.core.coordination;

@FunctionalInterface
public interface TranslationWorkerCoordinator {
    boolean executeIfAcquired(Runnable action);
}
