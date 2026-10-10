package ru.practicum.ewm.stats.aggregator.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import ru.practicum.ewm.stats.aggregator.service.SimilarityCalculator;
import ru.practicum.ewm.stats.avro.ActionTypeAvro;
import ru.practicum.ewm.stats.avro.EventSimilarityAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;
import ru.practicum.ewm.stats.serialization.ActionWeights;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserActionListenerTest {

    private static final String ACTIONS = "stats.user-actions.v1";
    private static final String SIMILARITIES = "stats.events-similarity.v1";
    private static final TopicPartition PARTITION = new TopicPartition(ACTIONS, 0);
    private static final Instant TIMESTAMP = Instant.ofEpochMilli(1_800_000_000_123L);
    @Mock
    private Consumer<Long, UserActionAvro> consumer;
    @Mock
    private KafkaTemplate<String, EventSimilarityAvro> kafkaTemplate;
    @Mock
    private Acknowledgment acknowledgment;
    @Captor
    private ArgumentCaptor<ProducerRecord<String, EventSimilarityAvro>> output;
    private SimilarityCalculator calculator;
    private UserActionListener listener;

    @BeforeEach
    void setUp() {
        calculator = new SimilarityCalculator(new ActionWeights(0.4, 0.8, 1.0));
        listener = new UserActionListener(calculator, kafkaTemplate, SIMILARITIES, 1000);
    }

    @Test
    void shouldPublishWithNormalizedKeyAndOriginalTimestampBeforeApplyingAndAcknowledging() {
        assign(null);
        apply(action(10, ActionTypeAvro.VIEW));
        UserActionAvro next = action(20, ActionTypeAvro.LIKE);
        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, EventSimilarityAvro>>any())).thenAnswer(invocation -> {
            assertThat(calculator.prepare(next).similarities()).hasSize(1);
            verifyNoInteractions(acknowledgment);
            return CompletableFuture.completedFuture(null);
        });

        listener.onAction(record(1, next), acknowledgment);

        verify(kafkaTemplate).send(output.capture());
        ProducerRecord<String, EventSimilarityAvro> sent = output.getValue();
        assertThat(sent.topic()).isEqualTo(SIMILARITIES);
        assertThat(sent.key()).isEqualTo("10:20");
        assertThat(sent.timestamp()).isEqualTo(TIMESTAMP.toEpochMilli());
        assertThat(sent.value().getTimestamp()).isEqualTo(TIMESTAMP);
        assertThat(sent.value().getScore()).isCloseTo(0.4 / Math.sqrt(0.4 * 1.0), within(1e-12));
        assertThat(calculator.prepare(next).similarities()).isEmpty();
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldRetryAllPairsAfterPartialPublicationFailure() {
        assign(null);
        apply(action(10, ActionTypeAvro.VIEW));
        apply(action(20, ActionTypeAvro.REGISTER));
        apply(action(30, ActionTypeAvro.LIKE));
        UserActionAvro next = action(40, ActionTypeAvro.LIKE);
        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, EventSimilarityAvro>>any()))
                .thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Брокер недоступен")))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> listener.onAction(record(3, next), acknowledgment))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(acknowledgment);
        assertThat(calculator.prepare(next).similarities()).hasSize(3);

        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, EventSimilarityAvro>>any())).thenReturn(CompletableFuture.completedFuture(null));
        listener.onAction(record(3, next), acknowledgment);

        verify(kafkaTemplate, times(6)).send(output.capture());
        assertThat(output.getAllValues().subList(0, 3)).extracting(ProducerRecord::key)
                .containsExactlyElementsOf(output.getAllValues().subList(3, 6).stream().map(ProducerRecord::key).toList());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldLeaveStateAndOffsetUnchangedWhenSendThrowsImmediately() {
        assign(null);
        apply(action(10, ActionTypeAvro.VIEW));
        UserActionAvro next = action(20, ActionTypeAvro.LIKE);
        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, EventSimilarityAvro>>any())).thenThrow(new IllegalStateException("Отправка запрещена"));

        assertThatThrownBy(() -> listener.onAction(record(1, next), acknowledgment))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calculator.prepare(next).similarities()).hasSize(1);
        verifyNoInteractions(acknowledgment);
    }

    @Test
    void shouldNotApplyOrAcknowledgeOnPublicationTimeout() {
        listener = new UserActionListener(calculator, kafkaTemplate, SIMILARITIES, 1);
        assign(null);
        apply(action(10, ActionTypeAvro.VIEW));
        UserActionAvro next = action(20, ActionTypeAvro.LIKE);
        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, EventSimilarityAvro>>any())).thenReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> listener.onAction(record(1, next), acknowledgment))
                .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(TimeoutException.class);
        assertThat(calculator.prepare(next).similarities()).hasSize(1);
        verifyNoInteractions(acknowledgment);
    }

    @Test
    void shouldRestoreHistoricalActionsWithoutRepublishingOrMovingOffsets() {
        assign(2L);

        listener.onAction(record(0, action(10, ActionTypeAvro.VIEW)), acknowledgment);
        listener.onAction(record(1, action(20, ActionTypeAvro.LIKE)), acknowledgment);

        verifyNoInteractions(kafkaTemplate, acknowledgment);
        assertThat(calculator.prepare(action(10, ActionTypeAvro.LIKE)).similarities()).hasSize(1);
        verify(consumer).seekToBeginning(Set.of(PARTITION));
    }

    @Test
    void shouldPublishTheFirstRecordAtTheCommittedBoundaryUsingRestoredWeights() {
        assign(2L);
        listener.onAction(record(0, action(10, ActionTypeAvro.VIEW)), acknowledgment);
        listener.onAction(record(1, action(20, ActionTypeAvro.LIKE)), acknowledgment);
        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, EventSimilarityAvro>>any())).thenReturn(CompletableFuture.completedFuture(null));

        listener.onAction(record(2, action(10, ActionTypeAvro.LIKE)), acknowledgment);

        verify(kafkaTemplate).send(output.capture());
        assertThat(output.getValue().value().getScore()).isEqualTo(1.0);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldPublishFromTheBeginningForANewConsumerGroup() {
        assign(null);
        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, EventSimilarityAvro>>any())).thenReturn(CompletableFuture.completedFuture(null));

        listener.onAction(record(0, action(10, ActionTypeAvro.VIEW)), acknowledgment);
        listener.onAction(record(1, action(20, ActionTypeAvro.LIKE)), acknowledgment);

        verify(kafkaTemplate).send(output.capture());
        assertThat(output.getValue().key()).isEqualTo("10:20");
        verify(acknowledgment, times(2)).acknowledge();
    }

    @Test
    void shouldAcknowledgeDisjointEventsWithoutPublishingAndKeepTheirWeights() {
        assign(null);
        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, EventSimilarityAvro>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        listener.onAction(record(0, new UserActionAvro(5L, 8L, ActionTypeAvro.REGISTER, TIMESTAMP)), acknowledgment);
        listener.onAction(record(1, new UserActionAvro(7L, 3L, ActionTypeAvro.REGISTER, TIMESTAMP)), acknowledgment);

        verifyNoInteractions(kafkaTemplate);
        verify(acknowledgment, times(2)).acknowledge();

        listener.onAction(record(2, new UserActionAvro(5L, 3L, ActionTypeAvro.VIEW, TIMESTAMP)), acknowledgment);

        verify(kafkaTemplate).send(output.capture());
        assertThat(output.getValue().key()).isEqualTo("3:8");
        assertThat(output.getValue().value().getScore()).isCloseTo(0.4 / Math.sqrt(1.2 * 0.8), within(1e-12));
        verify(acknowledgment, times(3)).acknowledge();
    }

    @Test
    void shouldAcknowledgeAnUnrelatedUsersActionAndKeepTheEventSum() {
        assign(null);
        apply(action(10, ActionTypeAvro.VIEW));
        apply(action(20, ActionTypeAvro.VIEW));
        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, EventSimilarityAvro>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        listener.onAction(record(2, new UserActionAvro(2L, 10L, ActionTypeAvro.LIKE, TIMESTAMP)), acknowledgment);

        verifyNoInteractions(kafkaTemplate);
        verify(acknowledgment).acknowledge();

        listener.onAction(record(3, new UserActionAvro(2L, 20L, ActionTypeAvro.VIEW, TIMESTAMP)), acknowledgment);

        verify(kafkaTemplate).send(output.capture());
        assertThat(output.getValue().key()).isEqualTo("10:20");
        assertThat(output.getValue().value().getScore()).isCloseTo(0.8 / Math.sqrt(1.4 * 0.8), within(1e-12));
        verify(acknowledgment, times(2)).acknowledge();
    }

    @Test
    void shouldResetTheWholeMatrixOnReassignment() {
        assign(2L);
        listener.onAction(record(0, action(10, ActionTypeAvro.LIKE)), acknowledgment);
        listener.onAction(record(1, action(20, ActionTypeAvro.LIKE)), acknowledgment);
        assign(null);
        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, EventSimilarityAvro>>any())).thenReturn(CompletableFuture.completedFuture(null));

        listener.onAction(record(0, action(30, ActionTypeAvro.VIEW)), acknowledgment);
        listener.onAction(record(1, action(40, ActionTypeAvro.VIEW)), acknowledgment);

        verify(kafkaTemplate).send(output.capture());
        assertThat(output.getValue().key()).isEqualTo("30:40");
    }

    @Test
    void shouldAcknowledgeAnUnchangedMaximumWithoutPublishingAgain() {
        assign(null);
        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, EventSimilarityAvro>>any())).thenReturn(CompletableFuture.completedFuture(null));
        listener.onAction(record(0, action(10, ActionTypeAvro.LIKE)), acknowledgment);
        listener.onAction(record(1, action(20, ActionTypeAvro.LIKE)), acknowledgment);

        listener.onAction(record(2, action(10, ActionTypeAvro.VIEW)), acknowledgment);

        verify(kafkaTemplate).send(ArgumentMatchers.<ProducerRecord<String, EventSimilarityAvro>>any());
        verify(acknowledgment, times(3)).acknowledge();
    }

    @Test
    void shouldRetryOnlyOffsetAcknowledgmentAfterSuccessfulPublication() {
        assign(null);
        apply(action(10, ActionTypeAvro.VIEW));
        UserActionAvro next = action(20, ActionTypeAvro.LIKE);
        when(kafkaTemplate.send(ArgumentMatchers.<ProducerRecord<String, EventSimilarityAvro>>any())).thenReturn(CompletableFuture.completedFuture(null));
        doThrow(new IllegalStateException("Фиксация offset недоступна"))
                .doNothing().when(acknowledgment).acknowledge();

        assertThatThrownBy(() -> listener.onAction(record(1, next), acknowledgment))
                .isInstanceOf(IllegalStateException.class);
        listener.onAction(record(1, next), acknowledgment);

        verify(kafkaTemplate).send(ArgumentMatchers.<ProducerRecord<String, EventSimilarityAvro>>any());
        verify(acknowledgment, times(2)).acknowledge();
    }

    @Test
    void shouldRejectSeveralAssignedPartitions() {
        assertThatThrownBy(() -> listener.onPartitionsAssigned(consumer,
                List.of(PARTITION, new TopicPartition(ACTIONS, 1))))
                .isInstanceOf(IllegalStateException.class);
        verify(consumer, never()).seekToBeginning(any());
    }

    @Test
    void shouldRejectAMultiPartitionTopicEvenWithOneAssignedPartition() {
        when(consumer.partitionsFor(ACTIONS)).thenReturn(List.of(partitionInfo(0), partitionInfo(1)));

        assertThatThrownBy(() -> listener.onPartitionsAssigned(consumer, Set.of(PARTITION)))
                .isInstanceOf(IllegalStateException.class);
        verify(consumer, never()).seekToBeginning(any());
    }

    @Test
    void shouldRejectRecoveryWhenTheBeginningOfTheHistoryWasDeleted() {
        stubAssignment(2L);
        when(consumer.beginningOffsets(Set.of(PARTITION))).thenReturn(Map.of(PARTITION, 1L));

        assertThatThrownBy(() -> listener.onPartitionsAssigned(consumer, Set.of(PARTITION)))
                .isInstanceOf(IllegalStateException.class);
        verify(consumer, never()).seekToBeginning(any());
    }

    @Test
    void shouldRejectACommittedOffsetBeyondTheCurrentLog() {
        stubAssignment(101L);

        assertThatThrownBy(() -> listener.onPartitionsAssigned(consumer, Set.of(PARTITION)))
                .isInstanceOf(IllegalStateException.class);
        verify(consumer, never()).seekToBeginning(any());
    }

    @Test
    void shouldNotProcessRecordsBeforeAssignmentAndRecoveryPreparation() {
        assertThatThrownBy(() -> listener.onAction(record(0, action(10, ActionTypeAvro.VIEW)), acknowledgment))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(kafkaTemplate, acknowledgment);
    }

    private void assign(Long committedOffset) {
        stubAssignment(committedOffset);
        listener.onPartitionsAssigned(consumer, Set.of(PARTITION));
    }

    private void stubAssignment(Long committedOffset) {
        when(consumer.partitionsFor(ACTIONS)).thenReturn(List.of(partitionInfo(0)));
        when(consumer.committed(Set.of(PARTITION))).thenReturn(committedOffset == null
                ? Map.of() : Map.of(PARTITION, new OffsetAndMetadata(committedOffset)));
        when(consumer.beginningOffsets(Set.of(PARTITION))).thenReturn(Map.of(PARTITION, 0L));
        when(consumer.endOffsets(Set.of(PARTITION))).thenReturn(Map.of(PARTITION, 100L));
    }

    private PartitionInfo partitionInfo(int partition) {
        return new PartitionInfo(ACTIONS, partition, null, new Node[0], new Node[0]);
    }

    private UserActionAvro action(long eventId, ActionTypeAvro type) {
        return new UserActionAvro(1L, eventId, type, TIMESTAMP);
    }

    private ConsumerRecord<Long, UserActionAvro> record(long offset, UserActionAvro action) {
        return new ConsumerRecord<>(ACTIONS, 0, offset, action.getUserId(), action);
    }

    private void apply(UserActionAvro action) {
        calculator.apply(calculator.prepare(action));
    }
}
