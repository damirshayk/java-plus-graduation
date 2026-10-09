package ru.practicum.ewm.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SharedOutboxRelayTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transaction;
    private SharedOutboxJdbc outbox;
    private KafkaTemplate<String, String> kafka;
    private SharedOutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE cleanup_outbox (
                    event_id VARCHAR(36) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    payload TEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        outbox = spy(new SharedOutboxJdbc(jdbc, new CleanupEventCodec(new ObjectMapper()), manager));
        kafka = mock(KafkaTemplate.class);
        relay = new SharedOutboxRelay(outbox, kafka, manager, "test-cleanup.v1", 2, 20);
    }

    @Test
    void enqueueMustRequireExistingTransactionAndRollbackWithIt() {
        assertThatThrownBy(() -> outbox.enqueue(event(1L))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            outbox.enqueue(event(1L));
            throw new IllegalStateException("Откат удаления");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero();
    }

    @Test
    void relayMustPublishBoundedBatchOutsideTransactionAndDeleteOnlyConfirmedRecords() {
        add(1L, 2L, 3L);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (invocation.<String>getArgument(1).equals("2")) {
                return CompletableFuture.failedFuture(new IllegalStateException("Брокер недоступен"));
            }
            return CompletableFuture.completedFuture(mock(SendResult.class));
        });

        transaction.executeWithoutResult(status -> relay.publishPending());

        assertThat(jdbc.queryForList("SELECT user_id FROM cleanup_outbox ORDER BY user_id", Long.class))
                .containsExactly(2L, 3L);
        verify(kafka, times(2)).send(eq("test-cleanup.v1"), anyString(), anyString());
        verify(outbox).deleteConfirmed(anyList());
    }

    @Test
    void timeoutMustKeepMessageForNextRelayAttempt() {
        add(1L);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        relay.publishPending();
        assertThat(count()).isEqualTo(1);
        verify(outbox, never()).deleteConfirmed(anyList());
    }

    @Test
    void crashAfterKafkaAckBeforeDatabaseDeleteMustAllowSafeRedelivery() {
        add(1L);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        doThrow(new IllegalStateException("Сбой после ack")).doCallRealMethod().when(outbox).deleteConfirmed(anyList());

        assertThatThrownBy(() -> relay.publishPending()).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isEqualTo(1);
        relay.publishPending();

        assertThat(count()).isZero();
        verify(kafka, times(2)).send(eq("test-cleanup.v1"), eq("1"), anyString());
    }

    private void add(Long... userIds) {
        for (Long userId : userIds) {
            transaction.executeWithoutResult(status -> outbox.enqueue(event(userId)));
        }
    }

    private CommonCleanupEvent event(Long userId) {
        return new CommonCleanupEvent(UUID.randomUUID(), CleanupEventType.USER_DELETED, userId, List.of());
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cleanup_outbox", Long.class);
    }
}
