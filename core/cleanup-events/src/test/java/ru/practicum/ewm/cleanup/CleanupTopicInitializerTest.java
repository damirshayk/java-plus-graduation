package ru.practicum.ewm.cleanup;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CleanupTopicInitializerTest {

    @Test
    void failedInitializationMustRetryOutsideTransactionAndStopAfterSuccess() {
        var manager = new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:topic-init", "sa", ""));
        KafkaAdmin admin = mock(KafkaAdmin.class);
        when(admin.initialize()).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return false;
        }).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return true;
        });
        var initializer = new CleanupTopicInitializer(admin, manager);
        var transaction = new TransactionTemplate(manager);

        for (int attempt = 0; attempt < 3; attempt++) {
            transaction.executeWithoutResult(status -> initializer.ensureTopic());
        }

        verify(admin, times(2)).initialize();
        verifyNoMoreInteractions(admin);
    }
}
