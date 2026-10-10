package ru.practicum.ewm.stats.aggregator.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import ru.practicum.ewm.stats.aggregator.service.SimilarityCalculator;
import ru.practicum.ewm.stats.avro.EventSimilarityAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class UserActionListener implements ConsumerAwareRebalanceListener {

    private final SimilarityCalculator calculator;
    private final KafkaTemplate<String, EventSimilarityAvro> kafkaTemplate;
    private final String similarityTopic;
    private final long sendTimeoutMs;
    private TopicPartition assignedPartition;
    private long replayBoundary;

    public UserActionListener(SimilarityCalculator calculator, KafkaTemplate<String, EventSimilarityAvro> kafkaTemplate,
                              @Value("${stats.kafka.topics.events-similarity}") String similarityTopic,
                              @Value("${stats.kafka.send-timeout-ms}") long sendTimeoutMs) {
        this.calculator = calculator;
        this.kafkaTemplate = kafkaTemplate;
        this.similarityTopic = similarityTopic;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @KafkaListener(id = "aggregator-user-actions", idIsGroup = false,
            topics = "${stats.kafka.topics.user-actions}", concurrency = "1")
    public void onAction(ConsumerRecord<Long, UserActionAvro> record, Acknowledgment acknowledgment) {
        if (assignedPartition == null || !assignedPartition.topic().equals(record.topic())
                || assignedPartition.partition() != record.partition()) {
            throw new IllegalStateException("Матрица не подготовлена для назначенной партиции");
        }
        var update = calculator.prepare(record.value());
        if (record.offset() < replayBoundary) {
            // История уже подтверждена брокером: восстанавливаем память, не откатывая offset и сходства Analyzer.
            calculator.apply(update);
            return;
        }

        List<CompletableFuture<SendResult<String, EventSimilarityAvro>>> publications = new ArrayList<>();
        for (EventSimilarityAvro similarity : update.similarities()) {
            String key = similarity.getEventA() + ":" + similarity.getEventB();
            publications.add(kafkaTemplate.send(new ProducerRecord<>(similarityTopic, null,
                    similarity.getTimestamp().toEpochMilli(), key, similarity)));
        }
        try {
            CompletableFuture.allOf(publications.toArray(new CompletableFuture<?>[0]))
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Отправка сходств прервана", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Не удалось подтвердить отправку всех сходств", exception);
        }
        calculator.apply(update);
        acknowledgment.acknowledge();
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        if (partitions.isEmpty()) {
            return;
        }
        if (partitions.size() != 1) {
            throw new IllegalStateException("Матрица агрегатора требует единственную партицию действий");
        }
        TopicPartition partition = partitions.iterator().next();
        var metadata = consumer.partitionsFor(partition.topic());
        if (partition.partition() != 0 || metadata == null || metadata.size() != 1) {
            throw new IllegalStateException("Топик действий должен иметь одну партицию");
        }
        Set<TopicPartition> assigned = Set.of(partition);
        OffsetAndMetadata committed = consumer.committed(assigned).get(partition);
        long snapshot = committed == null ? 0 : committed.offset();
        long beginning = consumer.beginningOffsets(assigned).get(partition);
        long end = consumer.endOffsets(assigned).get(partition);
        if (beginning != 0 || snapshot < 0 || snapshot > end) {
            throw new IllegalStateException("История действий недоступна для полного восстановления матрицы");
        }
        calculator.reset();
        consumer.seekToBeginning(assigned);
        assignedPartition = partition;
        replayBoundary = snapshot;
    }
}
