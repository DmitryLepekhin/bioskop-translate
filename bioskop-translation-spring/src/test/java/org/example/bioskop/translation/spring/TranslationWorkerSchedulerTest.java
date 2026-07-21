package org.example.bioskop.translation.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.example.bioskop.translation.core.TranslationWorker;
import org.example.bioskop.translation.core.coordination.TranslationWorkerCoordinator;
import org.junit.jupiter.api.Test;

class TranslationWorkerSchedulerTest {
    @Test
    void delegatesTheWholeWorkerCallThroughCoordinator() {
        CountingWorker worker = new CountingWorker();
        CountingCoordinator coordinator = new CountingCoordinator();

        new TranslationWorkerScheduler(worker, coordinator).poll();

        assertEquals(1, coordinator.invocations);
        assertEquals(1, worker.invocations);
    }

    private static final class CountingWorker extends TranslationWorker {
        private int invocations;

        private CountingWorker() {
            super(null, null, null);
        }

        @Override
        public boolean runOnce() {
            invocations++;
            return true;
        }
    }

    private static final class CountingCoordinator implements TranslationWorkerCoordinator {
        private int invocations;

        @Override
        public boolean executeIfAcquired(Runnable action) {
            invocations++;
            action.run();
            return true;
        }
    }
}
