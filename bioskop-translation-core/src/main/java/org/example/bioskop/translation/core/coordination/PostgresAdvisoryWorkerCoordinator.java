package org.example.bioskop.translation.core.coordination;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;

public final class PostgresAdvisoryWorkerCoordinator implements TranslationWorkerCoordinator {
    /** Stable namespace key for the Bioskop fleet-wide translation worker. */
    public static final long DEFAULT_LOCK_KEY = 4780106372145997131L;

    private final DataSource dataSource;
    private final long lockKey;

    public PostgresAdvisoryWorkerCoordinator(DataSource dataSource, long lockKey) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.lockKey = lockKey;
    }

    @Override
    public boolean executeIfAcquired(Runnable action) {
        Objects.requireNonNull(action, "action must not be null");
        try (Connection connection = dataSource.getConnection()) {
            if (!queryBoolean(connection, "select pg_try_advisory_lock(?)")) {
                return false;
            }

            RuntimeException actionFailure = null;
            try {
                action.run();
                return true;
            } catch (RuntimeException e) {
                actionFailure = e;
                throw e;
            } finally {
                try {
                    if (!queryBoolean(connection, "select pg_advisory_unlock(?)")) {
                        throw new SQLException("PostgreSQL session no longer owns the advisory lock");
                    }
                } catch (SQLException cleanupFailure) {
                    abort(connection, cleanupFailure);
                    DataAccessResourceFailureException translated = translate("release", cleanupFailure);
                    if (actionFailure != null) {
                        actionFailure.addSuppressed(translated);
                    } else {
                        throw translated;
                    }
                }
            }
        } catch (SQLException e) {
            throw translate("acquire", e);
        }
    }

    private boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lockKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("PostgreSQL advisory-lock query returned no row");
                }
                return result.getBoolean(1);
            }
        }
    }

    private void abort(Connection connection, SQLException cleanupFailure) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException abortFailure) {
            cleanupFailure.addSuppressed(abortFailure);
        }
    }

    private DataAccessResourceFailureException translate(String operation, SQLException cause) {
        return new DataAccessResourceFailureException(
            "Failed to " + operation + " PostgreSQL advisory lock " + lockKey,
            cause
        );
    }
}
