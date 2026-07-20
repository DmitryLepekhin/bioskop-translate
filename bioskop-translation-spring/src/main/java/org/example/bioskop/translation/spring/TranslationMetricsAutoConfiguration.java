package org.example.bioskop.translation.spring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.example.bioskop.translation.core.TranslationTelemetry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = TranslationAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
public class TranslationMetricsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    TranslationTelemetry translationTelemetry(MeterRegistry registry) {
        return new MicrometerTranslationTelemetry(registry);
    }

    private static final class MicrometerTranslationTelemetry implements TranslationTelemetry {
        private final Counter jobsClaimed;
        private final Counter jobsCompleted;
        private final Counter jobsFailed;
        private final Counter jobsRetried;
        private final Counter jobsReclaimed;
        private final Map<ProviderOutcome, Counter> providerCalls;
        private final Map<ProviderOutcome, Timer> providerDurations;

        private MicrometerTranslationTelemetry(MeterRegistry registry) {
            jobsClaimed = registry.counter("bioskop.translation.jobs.claimed");
            jobsCompleted = registry.counter("bioskop.translation.jobs.completed");
            jobsFailed = registry.counter("bioskop.translation.jobs.failed");
            jobsRetried = registry.counter("bioskop.translation.jobs.retried");
            jobsReclaimed = registry.counter("bioskop.translation.jobs.reclaimed");
            providerCalls = new EnumMap<>(ProviderOutcome.class);
            providerDurations = new EnumMap<>(ProviderOutcome.class);
            for (ProviderOutcome outcome : ProviderOutcome.values()) {
                String tag = outcome.name().toLowerCase();
                providerCalls.put(
                    outcome,
                    Counter.builder("bioskop.translation.provider.calls")
                        .tag("outcome", tag)
                        .register(registry)
                );
                providerDurations.put(
                    outcome,
                    Timer.builder("bioskop.translation.provider.duration")
                        .tag("outcome", tag)
                        .register(registry)
                );
            }
        }

        @Override
        public void jobClaimed(boolean retry, boolean reclaimed) {
            jobsClaimed.increment();
            if (retry) {
                jobsRetried.increment();
            }
            if (reclaimed) {
                jobsReclaimed.increment();
            }
        }

        @Override
        public void jobCompleted() {
            jobsCompleted.increment();
        }

        @Override
        public void jobFailed() {
            jobsFailed.increment();
        }

        @Override
        public void providerCall(Duration duration, ProviderOutcome outcome) {
            providerCalls.get(outcome).increment();
            providerDurations.get(outcome).record(duration);
        }
    }
}
