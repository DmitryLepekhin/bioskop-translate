package org.example.bioskop.translation.spring;

import org.example.bioskop.translation.core.TranslationWorker;
import org.example.bioskop.translation.core.coordination.TranslationWorkerCoordinator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@AutoConfiguration(after = TranslationAutoConfiguration.class)
@EnableScheduling
@ConditionalOnBean(TranslationWorker.class)
@ConditionalOnProperty(
    prefix = "bioskop.translation.worker",
    name = "enabled",
    havingValue = "true"
)
public class TranslationWorkerScheduler {
    private final TranslationWorker worker;
    private final TranslationWorkerCoordinator coordinator;

    public TranslationWorkerScheduler(TranslationWorker worker, TranslationWorkerCoordinator coordinator) {
        this.worker = worker;
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${bioskop.translation.worker.poll-delay:PT5S}")
    public void poll() {
        coordinator.executeIfAcquired(worker::runOnce);
    }
}
