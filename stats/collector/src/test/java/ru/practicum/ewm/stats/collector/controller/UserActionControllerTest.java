package ru.practicum.ewm.stats.collector.controller;

import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import ru.practicum.ewm.stats.avro.ActionTypeAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;
import ru.practicum.ewm.stats.collector.service.UserActionService;
import ru.practicum.ewm.stats.proto.ActionTypeProto;
import ru.practicum.ewm.stats.proto.UserActionProto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class UserActionControllerTest {

    private static final String TOPIC = "collector.contract.user-actions";
    private KafkaTemplate<Long, UserActionAvro> kafkaTemplate;
    private UserActionController controller;
    private RecordingObserver observer;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        controller = new UserActionController(new UserActionService(kafkaTemplate, TOPIC));
        observer = new RecordingObserver();
    }

    @ParameterizedTest
    @EnumSource(value = ActionTypeProto.class, names = {"ACTION_VIEW", "ACTION_REGISTER", "ACTION_LIKE"})
    void sendsEverySupportedAction(ActionTypeProto actionType) {
        CompletableFuture<SendResult<Long, UserActionAvro>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        controller.collectUserAction(validAction().setActionType(actionType).build(), observer);

        ProducerRecord<Long, UserActionAvro> record = sentRecord();
        ActionTypeAvro expectedType = switch (actionType) {
            case ACTION_VIEW -> ActionTypeAvro.VIEW;
            case ACTION_REGISTER -> ActionTypeAvro.REGISTER;
            case ACTION_LIKE -> ActionTypeAvro.LIKE;
            default -> throw new AssertionError("Неожиданный тип действия");
        };
        assertThat(record.value().getActionType()).isEqualTo(expectedType);
        future.complete(sendResult(record));
        assertSuccess(observer);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void preservesLargeIdentifiersFractionalTimestampAndKafkaMetadata() {
        CompletableFuture<SendResult<Long, UserActionAvro>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);
        Timestamp timestamp = Timestamp.newBuilder().setSeconds(1_725_432_100L).setNanos(987_654_321).build();

        controller.collectUserAction(validAction().setUserId(Long.MAX_VALUE).setEventId(Long.MAX_VALUE - 1)
                .setTimestamp(timestamp).build(), observer);

        ProducerRecord<Long, UserActionAvro> record = sentRecord();
        Instant expectedTimestamp = Instant.ofEpochSecond(timestamp.getSeconds(), 987_000_000);
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.partition()).isNull();
        assertThat(record.key()).isEqualTo(Long.MAX_VALUE);
        assertThat(record.timestamp()).isEqualTo(expectedTimestamp.toEpochMilli());
        assertThat(record.value().getUserId()).isEqualTo(Long.MAX_VALUE);
        assertThat(record.value().getEventId()).isEqualTo(Long.MAX_VALUE - 1);
        assertThat(record.value().getTimestamp()).isEqualTo(expectedTimestamp);
        future.complete(sendResult(record));
        assertSuccess(observer);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void waitsForKafkaAcknowledgementBeforeCompletingRpc() {
        CompletableFuture<SendResult<Long, UserActionAvro>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        controller.collectUserAction(validAction().build(), observer);

        assertThat(observer.values).isEmpty();
        assertThat(observer.completed).isFalse();
        assertThat(observer.error).isNull();
        future.complete(sendResult(sentRecord()));
        assertSuccess(observer);
    }

    @Test
    void reportsUnavailableWhenKafkaAcknowledgementFails() {
        CompletableFuture<SendResult<Long, UserActionAvro>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        controller.collectUserAction(validAction().build(), observer);
        future.completeExceptionally(new IllegalStateException("Внутренний адрес брокера и пароль"));

        assertFailure(observer, Status.Code.UNAVAILABLE);
        assertThat(Status.fromThrowable(observer.error).getDescription())
                .doesNotContain("адрес", "пароль");
        assertThat(observer.error.getCause()).isNull();
    }

    @Test
    void reportsUnavailableWhenKafkaSendThrowsImmediately() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenThrow(new IllegalArgumentException("Внутренние настройки сериализатора"));

        controller.collectUserAction(validAction().build(), observer);

        assertFailure(observer, Status.Code.UNAVAILABLE);
        assertThat(Status.fromThrowable(observer.error).getDescription()).doesNotContain("сериализатора");
        assertThat(observer.error.getCause()).isNull();
    }

    @Test
    void keepsAcknowledgementsIndependentForConcurrentRequests() {
        CompletableFuture<SendResult<Long, UserActionAvro>> first = new CompletableFuture<>();
        CompletableFuture<SendResult<Long, UserActionAvro>> second = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(first).thenReturn(second);
        RecordingObserver secondObserver = new RecordingObserver();

        controller.collectUserAction(validAction().build(), observer);
        controller.collectUserAction(validAction().setEventId(2).build(), secondObserver);
        ArgumentCaptor<ProducerRecord<Long, UserActionAvro>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(2)).send(captor.capture());
        second.complete(sendResult(captor.getAllValues().get(1)));

        assertSuccess(secondObserver);
        assertThat(observer.values).isEmpty();
        assertThat(observer.completed).isFalse();
        first.completeExceptionally(new IllegalStateException("Брокер недоступен"));
        assertFailure(observer, Status.Code.UNAVAILABLE);
        assertSuccess(secondObserver);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-1,1", "-9223372036854775808,1", "1,0", "1,-1", "1,-9223372036854775808"})
    void rejectsInvalidIdentifiersBeforeSending(long userId, long eventId) {
        controller.collectUserAction(validAction().setUserId(userId).setEventId(eventId).build(), observer);

        assertFailure(observer, Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(kafkaTemplate);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 3, Integer.MAX_VALUE})
    void rejectsUnknownActionTypeBeforeSending(int actionType) {
        controller.collectUserAction(validAction().setActionTypeValue(actionType).build(), observer);

        assertFailure(observer, Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void rejectsMissingTimestampBeforeSending() {
        controller.collectUserAction(validAction().clearTimestamp().build(), observer);

        assertFailure(observer, Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(kafkaTemplate);
    }

    @ParameterizedTest
    @CsvSource({"-62135596801,0", "253402300800,0", "1000,-1", "1000,1000000000"})
    void rejectsInvalidTimestampBeforeSending(long seconds, int nanos) {
        Timestamp timestamp = Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();

        controller.collectUserAction(validAction().setTimestamp(timestamp).build(), observer);

        assertFailure(observer, Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(kafkaTemplate);
    }

    @ParameterizedTest
    @CsvSource({"-62135596800,0", "-1,999999999"})
    void rejectsTimestampUnsupportedByKafkaBeforeSending(long seconds, int nanos) {
        Timestamp timestamp = Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();

        controller.collectUserAction(validAction().setTimestamp(timestamp).build(), observer);

        assertFailure(observer, Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(kafkaTemplate);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "253402300799,999999999"})
    void acceptsTimestampBoundaries(long seconds, int nanos) {
        CompletableFuture<SendResult<Long, UserActionAvro>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        controller.collectUserAction(validAction()
                .setTimestamp(Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos)).build(), observer);

        ProducerRecord<Long, UserActionAvro> record = sentRecord();
        assertThat(record.value().getTimestamp()).isEqualTo(Instant.ofEpochSecond(seconds, nanos)
                .truncatedTo(ChronoUnit.MILLIS));
        future.complete(sendResult(record));
        assertSuccess(observer);
    }

    @Test
    void rejectsNullRequestBeforeSending() {
        controller.collectUserAction(null, observer);

        assertFailure(observer, Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(kafkaTemplate);
    }

    private UserActionProto.Builder validAction() {
        return UserActionProto.newBuilder().setUserId(1).setEventId(1).setActionType(ActionTypeProto.ACTION_VIEW)
                .setTimestamp(Timestamp.newBuilder().setSeconds(1_725_432_100L).setNanos(123_000_000));
    }

    private ProducerRecord<Long, UserActionAvro> sentRecord() {
        ArgumentCaptor<ProducerRecord<Long, UserActionAvro>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private SendResult<Long, UserActionAvro> sendResult(ProducerRecord<Long, UserActionAvro> record) {
        return new SendResult<>(record,
                new RecordMetadata(new TopicPartition(record.topic(), 0), 0, 0, record.timestamp(), Long.BYTES, 100));
    }

    private void assertSuccess(RecordingObserver result) {
        assertThat(result.values).containsExactly(Empty.getDefaultInstance());
        assertThat(result.completed).isTrue();
        assertThat(result.error).isNull();
    }

    private void assertFailure(RecordingObserver result, Status.Code statusCode) {
        assertThat(result.error).isNotNull();
        assertThat(Status.fromThrowable(result.error).getCode()).isEqualTo(statusCode);
        assertThat(result.values).isEmpty();
        assertThat(result.completed).isFalse();
    }

    private static class RecordingObserver implements StreamObserver<Empty> {

        private final List<Empty> values = new ArrayList<>();
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(Empty value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable failure) {
            error = failure;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
