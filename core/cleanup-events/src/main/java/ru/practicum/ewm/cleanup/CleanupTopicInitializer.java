package ru.practicum.ewm.cleanup;

import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class CleanupTopicInitializer {
    private final KafkaAdmin admin;
    private final TransactionTemplate withoutTransaction;
    private boolean initialized;

    public CleanupTopicInitializer(KafkaAdmin admin, PlatformTransactionManager manager) {
        this.admin = admin;
        withoutTransaction = new TransactionTemplate(manager);
        withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Scheduled(fixedDelayString = "${ewm.cleanup.topic-init-delay-ms:5000}")
    public synchronized void ensureTopic() {
        if (!initialized) {
            initialized = Boolean.TRUE.equals(withoutTransaction.execute(status -> admin.initialize()));
        }
    }
}
