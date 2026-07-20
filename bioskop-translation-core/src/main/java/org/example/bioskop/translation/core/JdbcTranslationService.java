package org.example.bioskop.translation.core;

import java.util.List;
import java.util.Objects;
import org.example.bioskop.translation.core.ai.AiTranslationClient;
import org.example.bioskop.translation.core.ai.CueTranslation;
import org.example.bioskop.translation.core.ai.TranslationBatchRequest;
import org.example.bioskop.translation.core.ai.TranslationResponseValidator;
import org.example.bioskop.translation.core.context.TranslationContext;
import org.example.bioskop.translation.core.context.TranslationContextLoader;
import org.example.bioskop.translation.core.persistence.JdbcTranslationRepository;
import org.example.bioskop.translation.core.persistence.TranslationJobAttemptRecord;
import org.example.bioskop.translation.core.persistence.TranslationJobRecord;
import org.example.bioskop.translation.core.srt.SrtParser;
import org.example.bioskop.translation.core.srt.SrtWriter;
import org.example.bioskop.translation.core.srt.SubtitleCue;
import org.example.bioskop.translation.core.storage.TranslationStorage;

public class JdbcTranslationService implements TranslationService {
    private final JdbcTranslationRepository repository;
    private final TranslationStorage storage;
    private final AiTranslationClient aiClient;
    private final SrtParser srtParser;
    private final SrtWriter srtWriter;
    private final TranslationServiceProperties properties;
    private final TranslationContextLoader contextLoader;
    private final TranslationTargetPathResolver targetPathResolver;

    public JdbcTranslationService(
        JdbcTranslationRepository repository,
        TranslationStorage storage,
        AiTranslationClient aiClient
    ) {
        this(
            repository,
            storage,
            aiClient,
            new SrtParser(),
            new SrtWriter(),
            TranslationServiceProperties.defaults(),
            new TranslationContextLoader(storage),
            new TranslationTargetPathResolver()
        );
    }

    public JdbcTranslationService(
        JdbcTranslationRepository repository,
        TranslationStorage storage,
        AiTranslationClient aiClient,
        SrtParser srtParser,
        SrtWriter srtWriter,
        TranslationServiceProperties properties,
        TranslationContextLoader contextLoader
    ) {
        this(
            repository,
            storage,
            aiClient,
            srtParser,
            srtWriter,
            properties,
            contextLoader,
            new TranslationTargetPathResolver()
        );
    }

    public JdbcTranslationService(
        JdbcTranslationRepository repository,
        TranslationStorage storage,
        AiTranslationClient aiClient,
        SrtParser srtParser,
        SrtWriter srtWriter,
        TranslationServiceProperties properties,
        TranslationContextLoader contextLoader,
        TranslationTargetPathResolver targetPathResolver
    ) {
        this.repository = repository;
        this.storage = storage;
        this.aiClient = aiClient;
        this.srtParser = srtParser;
        this.srtWriter = srtWriter;
        this.properties = properties;
        this.contextLoader = contextLoader;
        this.targetPathResolver = targetPathResolver;
    }

    @Override
    public TranslationResponse requestTranslation(TranslationRequest request) {
        validateRequest(request);
        String sourcePath = request.sourcePath().trim();
        String sourceLang = request.sourceLang().trim();
        String targetLang = request.targetLang().trim();
        String targetPath = targetPathResolver.resolve(sourcePath, targetLang, request.targetPath());
        if (sourcePath.equals(targetPath)) {
            throw new IllegalArgumentException("targetPath must not equal sourcePath");
        }

        TranslationJobRecord job = repository.findJob(request.sourceTextId(), targetLang)
            .orElseGet(() -> repository.createOrGetJob(
                request.sourceTextId(),
                sourcePath,
                sourceLang,
                targetLang,
                targetPath
            ));
        validateCompatible(job, sourcePath, sourceLang, targetPath);
        return toResponse(job);
    }

    @Override
    public TranslationStatusResponse getStatus(java.util.UUID sourceId, String targetLang) {
        if (sourceId == null) {
            throw new IllegalArgumentException("sourceId must not be null");
        }
        if (targetLang == null || targetLang.isBlank()) {
            throw new IllegalArgumentException("targetLang must not be blank");
        }
        String normalizedTargetLang = targetLang.trim();
        return repository.findJob(sourceId, normalizedTargetLang)
            .map(this::toStatusResponse)
            .orElseGet(() -> new TranslationStatusResponse(
                sourceId,
                null,
                null,
                null,
                normalizedTargetLang,
                null,
                "translation job not found"
            ));
    }

    public void executeJob(TranslationJobRecord job) {
        TranslationJobAttemptRecord attempt = repository.createAttempt(
            job.id(),
            job.attempts(),
            TranslationStatus.IN_PROGRESS
        );
        try {
            if (storage.exists(job.targetPath())) {
                repository.finishAttempt(attempt.id(), TranslationStatus.COMPLETED, null, null);
                repository.updateJobStatus(job.id(), TranslationStatus.COMPLETED, null, null);
                return;
            }

            TranslationContext context = contextLoader.loadForSourcePath(job.sourcePath());
            List<SubtitleCue> cues = contextLoader.attachSpeakers(
                srtParser.parse(storage.readText(job.sourcePath())),
                context.speakersByCueId()
            );
            TranslationFormat mode = hasContext(context) ? TranslationFormat.ADAPTED : TranslationFormat.QUICK;
            List<SubtitleCue> translatedCues = translateCues(job, mode, cues, context);
            storage.writeText(job.targetPath(), srtWriter.write(translatedCues));

            repository.finishAttempt(attempt.id(), TranslationStatus.COMPLETED, null, null);
            repository.updateJobStatus(job.id(), TranslationStatus.COMPLETED, null, null);
        } catch (RuntimeException e) {
            String message = e.getMessage();
            repository.finishAttempt(attempt.id(), TranslationStatus.FAILED, e.getClass().getSimpleName(), message);
            repository.updateJobStatus(job.id(), TranslationStatus.FAILED, e.getClass().getSimpleName(), message);
            throw e;
        }
    }

    public void resetStaleInProgress() {
        for (TranslationJobRecord job : repository.findStaleInProgress(properties.inProgressTimeout())) {
            if (storage.exists(job.targetPath())) {
                repository.updateJobStatus(job.id(), TranslationStatus.COMPLETED, null, null);
            } else if (job.attempts() < properties.maxAttempts()) {
                repository.updateJobStatus(job.id(), TranslationStatus.PENDING, null, null);
            } else {
                repository.updateJobStatus(
                    job.id(),
                    TranslationStatus.FAILED,
                    "MaxAttemptsExceeded",
                    "Maximum translation attempts exceeded"
                );
            }
        }
    }

    private void validateCompatible(
        TranslationJobRecord job,
        String sourcePath,
        String sourceLang,
        String targetPath
    ) {
        if (!job.sourcePath().equals(sourcePath)) {
            throw new IllegalArgumentException("sourcePath does not match existing translation job");
        }
        if (!job.sourceLang().equals(sourceLang)) {
            throw new IllegalArgumentException("sourceLang does not match existing translation job");
        }
        if (!job.targetPath().equals(targetPath)) {
            throw new IllegalArgumentException("targetPath does not match existing translation job");
        }
    }

    private List<SubtitleCue> translateCues(
        TranslationJobRecord job,
        TranslationFormat mode,
        List<SubtitleCue> cues,
        TranslationContext context
    ) {
        List<CueTranslation> translations = aiClient.translateBatch(new TranslationBatchRequest(
            job.sourceLang(),
            job.targetLang(),
            mode,
            cues,
            context
        ));
        TranslationResponseValidator.validateCoverage(cues, translations);
        return cues.stream()
            .map(cue -> cue.withText(findTranslation(cue.id(), translations)))
            .toList();
    }

    private String findTranslation(int cueId, List<CueTranslation> translations) {
        return translations.stream()
            .filter(translation -> translation.cueId() == cueId)
            .findFirst()
            .map(CueTranslation::text)
            .orElseThrow();
    }

    private boolean hasContext(TranslationContext context) {
        return (context.notes() != null && !context.notes().isBlank())
            || !context.characters().isEmpty()
            || !context.speakersByCueId().isEmpty();
    }

    private TranslationResponse toResponse(TranslationJobRecord job) {
        return new TranslationResponse(
            job.sourceTextId(),
            job.sourcePath(),
            job.targetPath(),
            job.sourceLang(),
            job.targetLang(),
            job.status(),
            job.errorMessage()
        );
    }

    private TranslationStatusResponse toStatusResponse(TranslationJobRecord job) {
        return new TranslationStatusResponse(
            job.sourceTextId(),
            job.sourcePath(),
            job.targetPath(),
            job.sourceLang(),
            job.targetLang(),
            job.status(),
            job.errorMessage()
        );
    }

    private void validateRequest(TranslationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.sourceTextId() == null) {
            throw new IllegalArgumentException("sourceTextId must not be null");
        }
        if (request.sourcePath() == null || request.sourcePath().isBlank()) {
            throw new IllegalArgumentException("sourcePath must not be blank");
        }
        if (request.sourceLang() == null || request.sourceLang().isBlank()) {
            throw new IllegalArgumentException("sourceLang must not be blank");
        }
        if (request.targetLang() == null || request.targetLang().isBlank()) {
            throw new IllegalArgumentException("targetLang must not be blank");
        }
    }
}
