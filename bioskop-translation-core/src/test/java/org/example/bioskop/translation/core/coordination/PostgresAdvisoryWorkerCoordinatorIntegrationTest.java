package org.example.bioskop.translation.core.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresAdvisoryWorkerCoordinatorIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void onlyOneSessionWinsAndLockIsReleasedAfterAction() throws Exception {
        var dataSource = dataSource();
        var first = new PostgresAdvisoryWorkerCoordinator(dataSource, 101L);
        var second = new PostgresAdvisoryWorkerCoordinator(dataSource, 101L);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean secondInvoked = new AtomicBoolean();

        Thread winner = new Thread(() -> first.executeIfAcquired(() -> {
            acquired.countDown();
            await(release);
        }));
        winner.start();
        assertTrue(acquired.await(5, TimeUnit.SECONDS));

        assertFalse(second.executeIfAcquired(() -> secondInvoked.set(true)));
        assertFalse(secondInvoked.get());
        release.countDown();
        winner.join(5_000);

        assertTrue(second.executeIfAcquired(() -> secondInvoked.set(true)));
        assertTrue(secondInvoked.get());
    }

    @Test
    void actionFailureStillReleasesLockAndPreservesFailure() {
        var first = new PostgresAdvisoryWorkerCoordinator(dataSource(), 102L);
        var second = new PostgresAdvisoryWorkerCoordinator(dataSource(), 102L);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> first.executeIfAcquired(() -> { throw new IllegalStateException("worker failed"); })
        );

        assertEquals("worker failed", failure.getMessage());
        assertTrue(second.executeIfAcquired(() -> { }));
    }

    @Test
    void differentKeysDoNotBlockAndClosingSessionReleasesLock() throws Exception {
        var dataSource = dataSource();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select pg_advisory_lock(?)")) {
            statement.setLong(1, 103L);
            statement.execute();
            assertTrue(new PostgresAdvisoryWorkerCoordinator(dataSource, 104L).executeIfAcquired(() -> { }));
            assertFalse(new PostgresAdvisoryWorkerCoordinator(dataSource, 103L).executeIfAcquired(() -> { }));
        }

        assertTrue(new PostgresAdvisoryWorkerCoordinator(dataSource, 103L).executeIfAcquired(() -> { }));
    }

    @Test
    void databaseFailureNeverInvokesAction() {
        DriverManagerDataSource broken = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/missing");
        AtomicBoolean invoked = new AtomicBoolean();

        assertThrows(
            DataAccessResourceFailureException.class,
            () -> new PostgresAdvisoryWorkerCoordinator(broken, 105L)
                .executeIfAcquired(() -> invoked.set(true))
        );
        assertFalse(invoked.get());
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
