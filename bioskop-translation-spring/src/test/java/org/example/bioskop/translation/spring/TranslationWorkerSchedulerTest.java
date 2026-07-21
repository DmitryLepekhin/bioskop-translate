package org.example.bioskop.translation.spring;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.example.bioskop.translation.core.TranslationWorker;
import org.example.bioskop.translation.core.coordination.TranslationWorkerCoordinator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class TranslationWorkerSchedulerTest {
    @Test
    void delegatesTheWholeWorkerCallThroughCoordinator() {
        TranslationWorker worker = mock(TranslationWorker.class);
        TranslationWorkerCoordinator coordinator = mock(TranslationWorkerCoordinator.class);

        new TranslationWorkerScheduler(worker, coordinator).poll();

        verify(coordinator).executeIfAcquired(ArgumentMatchers.any(Runnable.class));
    }
}
