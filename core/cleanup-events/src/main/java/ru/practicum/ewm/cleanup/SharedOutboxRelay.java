package ru.practicum.ewm.cleanup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class SharedOutboxRelay {
    private final SharedOutboxJdbc outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate withoutTransaction;
    private final String topic;
    private final int batchSize;
    private final long sendTimeoutMs;

    public SharedOutboxRelay(SharedOutboxJdbc outbox, KafkaTemplate<String, String> kafka,
                             PlatformTransactionManager manager, String topic, int batchSize, long sendTimeoutMs) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
        withoutTransaction = new TransactionTemplate(manager);
        withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Scheduled(fixedDelayString = "${ewm.cleanup.relay-delay-ms:1000}")
    public synchronized void publishPending() {
        withoutTransaction.executeWithoutResult(status -> {
            List<UUID> confirmed = new ArrayList<>();
            for (SharedOutboxJdbc.PendingMessage message : outbox.pending(batchSize)) {
                try {
                    kafka.send(topic, message.userId().toString(), message.payload())
                            .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                    confirmed.add(message.eventId());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException | TimeoutException | RuntimeException exception) {
                    log.warn("Доставка очистки временно недоступна, событие {} сохранено в outbox", message.eventId(), exception);
                    break;
                }
            }
            if (!confirmed.isEmpty()) {
                outbox.deleteConfirmed(confirmed);
            }
        });
    }
}
