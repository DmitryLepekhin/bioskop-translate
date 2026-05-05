package org.example.bioskop.translation.core.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.example.bioskop.translation.core.TranslationStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcTranslationRepository {
    private final JdbcTemplate jdbc;

    public JdbcTranslationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TranslationJobRecord createJob(
        UUID sourceTextId,
        String sourcePath,
        String sourceLang,
        String targetLang,
        String targetPath
    ) {
        Instant now = Instant.now();
        TranslationJobRecord job = new TranslationJobRecord(
            UUID.randomUUID(),
            sourceTextId,
            sourcePath,
            sourceLang,
            targetLang,
            targetPath,
            TranslationStatus.PENDING,
            0,
            null,
            null,
            now,
            now,
            null
        );
        jdbc.update("""
            insert into translation_job (
                id, source_text_id, source_path, source_lang, target_lang, target_path,
                status, attempts, error_code, error_message, created_at, updated_at, completed_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            job.id(),
            job.sourceTextId(),
            job.sourcePath(),
            job.sourceLang(),
            job.targetLang(),
            job.targetPath(),
            TranslationEnumMapper.statusId(job.status()),
            job.attempts(),
            job.errorCode(),
            job.errorMessage(),
            Timestamp.from(job.createdAt()),
            Timestamp.from(job.updatedAt()),
            null
        );
        return job;
    }

    public Optional<TranslationJobRecord> findJob(UUID sourceTextId, String targetLang) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                select * from translation_job
                where source_text_id = ? and target_lang = ?
                """,
                this::mapJob,
                sourceTextId,
                targetLang
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<TranslationJobRecord> findJob(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "select * from translation_job where id = ?",
                this::mapJob,
                id
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public TranslationJobRecord updateJobStatus(
        UUID jobId,
        TranslationStatus status,
        String errorCode,
        String errorMessage
    ) {
        Instant now = Instant.now();
        Timestamp completedAt = status == TranslationStatus.COMPLETED ? Timestamp.from(now) : null;
        return jdbc.queryForObject("""
            update translation_job
            set status = ?, error_code = ?, error_message = ?, updated_at = ?, completed_at = ?
            where id = ?
            returning *
            """,
            this::mapJob,
            TranslationEnumMapper.statusId(status),
            errorCode,
            errorMessage,
            Timestamp.from(now),
            completedAt,
            jobId
        );
    }

    public Optional<TranslationJobRecord> claimNextPending(int maxAttempts) {
        List<TranslationJobRecord> jobs = jdbc.query("""
            update translation_job
            set status = ?, attempts = attempts + 1, error_code = null, error_message = null, updated_at = ?
            where id = (
                select id
                from translation_job
                where status = ? and attempts < ?
                order by created_at
                for update skip locked
                limit 1
            )
            returning *
            """,
            this::mapJob,
            TranslationEnumMapper.statusId(TranslationStatus.IN_PROGRESS),
            Timestamp.from(Instant.now()),
            TranslationEnumMapper.statusId(TranslationStatus.PENDING),
            maxAttempts
        );
        return jobs.stream().findFirst();
    }

    public int failPendingExhausted(int maxAttempts) {
        return jdbc.update("""
            update translation_job
            set status = ?, error_code = ?, error_message = ?, updated_at = ?
            where status = ? and attempts >= ?
            """,
            TranslationEnumMapper.statusId(TranslationStatus.FAILED),
            "MaxAttemptsExceeded",
            "Maximum translation attempts exceeded",
            Timestamp.from(Instant.now()),
            TranslationEnumMapper.statusId(TranslationStatus.PENDING),
            maxAttempts
        );
    }

    public int requeueFailed(UUID jobId) {
        return jdbc.update("""
            update translation_job
            set status = ?, error_code = null, error_message = null, updated_at = ?, completed_at = null
            where id = ? and status = ?
            """,
            TranslationEnumMapper.statusId(TranslationStatus.PENDING),
            Timestamp.from(Instant.now()),
            jobId,
            TranslationEnumMapper.statusId(TranslationStatus.FAILED)
        );
    }

    public List<TranslationJobRecord> findStaleInProgress(Duration timeout) {
        return jdbc.query("""
            select *
            from translation_job
            where status = ? and updated_at < ?
            order by updated_at
            """,
            this::mapJob,
            TranslationEnumMapper.statusId(TranslationStatus.IN_PROGRESS),
            Timestamp.from(Instant.now().minus(timeout))
        );
    }

    public TranslationJobAttemptRecord createAttempt(UUID jobId, int attemptNo, TranslationStatus status) {
        Instant now = Instant.now();
        TranslationJobAttemptRecord attempt = new TranslationJobAttemptRecord(
            UUID.randomUUID(),
            jobId,
            attemptNo,
            status,
            now,
            null,
            null,
            null
        );
        jdbc.update("""
            insert into translation_job_attempt (
                id, job_id, attempt_no, status, started_at, finished_at, error_code, error_message
            )
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            attempt.id(),
            attempt.jobId(),
            attempt.attemptNo(),
            TranslationEnumMapper.statusId(attempt.status()),
            Timestamp.from(attempt.startedAt()),
            null,
            null,
            null
        );
        return attempt;
    }

    public void finishAttempt(UUID attemptId, TranslationStatus status, String errorCode, String errorMessage) {
        jdbc.update("""
            update translation_job_attempt
            set status = ?, finished_at = ?, error_code = ?, error_message = ?
            where id = ?
            """,
            TranslationEnumMapper.statusId(status),
            Timestamp.from(Instant.now()),
            errorCode,
            errorMessage,
            attemptId
        );
    }

    public List<TranslationJobAttemptRecord> findAttempts(UUID jobId) {
        return jdbc.query(
            "select * from translation_job_attempt where job_id = ? order by attempt_no",
            this::mapAttempt,
            jobId
        );
    }

    private TranslationJobRecord mapJob(ResultSet rs, int rowNum) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new TranslationJobRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("source_text_id", UUID.class),
            rs.getString("source_path"),
            rs.getString("source_lang"),
            rs.getString("target_lang"),
            rs.getString("target_path"),
            TranslationEnumMapper.statusFromId(rs.getInt("status")),
            rs.getInt("attempts"),
            rs.getString("error_code"),
            rs.getString("error_message"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            completedAt == null ? null : completedAt.toInstant()
        );
    }

    private TranslationJobAttemptRecord mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new TranslationJobAttemptRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("job_id", UUID.class),
            rs.getInt("attempt_no"),
            TranslationEnumMapper.statusFromId(rs.getInt("status")),
            rs.getTimestamp("started_at").toInstant(),
            finishedAt == null ? null : finishedAt.toInstant(),
            rs.getString("error_code"),
            rs.getString("error_message")
        );
    }
}
