package ru.practicum.ewm.stats.proto;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Timestamp;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import ru.practicum.ewm.stats.proto.collector.Collector;
import ru.practicum.ewm.stats.proto.dashboard.Dashboard;

import java.util.List;

import static com.google.protobuf.Descriptors.FieldDescriptor.Type.DOUBLE;
import static com.google.protobuf.Descriptors.FieldDescriptor.Type.ENUM;
import static com.google.protobuf.Descriptors.FieldDescriptor.Type.INT64;
import static com.google.protobuf.Descriptors.FieldDescriptor.Type.MESSAGE;
import static com.google.protobuf.Descriptors.FieldDescriptor.Type.SINT32;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ProtoContractTest {

    @Test
    void shouldPreserveUserActionContract() {
        Descriptors.Descriptor descriptor = UserActionProto.getDescriptor();

        assertThat(descriptor.getFullName()).isEqualTo("stats.message.UserActionProto");
        assertThat(descriptor.getFile().toProto().getSyntax()).isEqualTo("proto3");
        assertThat(descriptor.getFile().getOptions().getJavaPackage()).isEqualTo("ru.practicum.ewm.stats.proto");
        assertThat(descriptor.getFile().getOptions().getJavaMultipleFiles()).isTrue();
        assertThat(descriptor.getFields())
                .extracting(Descriptors.FieldDescriptor::getName, Descriptors.FieldDescriptor::getNumber,
                        Descriptors.FieldDescriptor::getType, Descriptors.FieldDescriptor::isRepeated)
                .containsExactly(tuple("user_id", 1, INT64, false), tuple("event_id", 2, INT64, false),
                        tuple("action_type", 3, ENUM, false), tuple("timestamp", 4, MESSAGE, false));
        assertThat(descriptor.findFieldByName("timestamp").getMessageType()).isEqualTo(Timestamp.getDescriptor());
        assertThat(descriptor.findFieldByName("action_type").getEnumType()).isEqualTo(ActionTypeProto.getDescriptor());
        assertThat(ActionTypeProto.getDescriptor().getValues())
                .extracting(Descriptors.EnumValueDescriptor::getName, Descriptors.EnumValueDescriptor::getNumber)
                .containsExactly(tuple("ACTION_VIEW", 0), tuple("ACTION_REGISTER", 1), tuple("ACTION_LIKE", 2));
    }

    @Test
    void shouldPreserveRecommendationMessagesContract() {
        assertThat(UserPredictionsRequestProto.getDescriptor().getFullName())
                .isEqualTo("stats.message.UserPredictionsRequestProto");
        assertFields(UserPredictionsRequestProto.getDescriptor(),
                tuple("user_id", 1, INT64, false), tuple("max_results", 2, SINT32, false));
        assertFields(SimilarEventsRequestProto.getDescriptor(),
                tuple("event_id", 1, INT64, false), tuple("user_id", 2, INT64, false),
                tuple("max_results", 3, SINT32, false));
        assertFields(InteractionsCountRequestProto.getDescriptor(), tuple("event_id", 1, INT64, true));
        assertFields(RecommendedEventProto.getDescriptor(),
                tuple("event_id", 1, INT64, false), tuple("score", 2, DOUBLE, false));
    }

    @Test
    void shouldPreserveGrpcMethodsAndStreamingContract() {
        Descriptors.ServiceDescriptor collector = Collector.getDescriptor().findServiceByName("UserActionController");
        Descriptors.ServiceDescriptor dashboard = Dashboard.getDescriptor()
                .findServiceByName("RecommendationsController");

        assertThat(collector.getFullName()).isEqualTo("stats.service.collector.UserActionController");
        assertThat(collector.getFile().getOptions().getJavaPackage())
                .isEqualTo("ru.practicum.ewm.stats.proto.collector");
        assertThat(collector.getMethods()).extracting(Descriptors.MethodDescriptor::getName)
                .containsExactly("CollectUserAction");
        assertMethod(collector, "CollectUserAction", "stats.message.UserActionProto", "google.protobuf.Empty", false);
        assertThat(dashboard.getFullName()).isEqualTo("stats.service.dashboard.RecommendationsController");
        assertThat(dashboard.getFile().getOptions().getJavaPackage())
                .isEqualTo("ru.practicum.ewm.stats.proto.dashboard");
        assertThat(dashboard.getMethods()).extracting(Descriptors.MethodDescriptor::getName)
                .containsExactly("GetRecommendationsForUser", "GetSimilarEvents", "GetInteractionsCount");
        assertMethod(dashboard, "GetRecommendationsForUser", "stats.message.UserPredictionsRequestProto",
                "stats.message.RecommendedEventProto", true);
        assertMethod(dashboard, "GetSimilarEvents", "stats.message.SimilarEventsRequestProto",
                "stats.message.RecommendedEventProto", true);
        assertMethod(dashboard, "GetInteractionsCount", "stats.message.InteractionsCountRequestProto",
                "stats.message.RecommendedEventProto", true);
    }

    @Test
    void shouldRoundTripLargeIdentifiersAndTimestampForEveryAction() throws Exception {
        Timestamp timestamp = Timestamp.newBuilder().setSeconds(1_800_000_000L).setNanos(123_456_789).build();

        for (ActionTypeProto action : List.of(ActionTypeProto.ACTION_VIEW,
                ActionTypeProto.ACTION_REGISTER, ActionTypeProto.ACTION_LIKE)) {
            UserActionProto original = UserActionProto.newBuilder().setUserId(3_000_000_000L)
                    .setEventId(Long.MAX_VALUE).setActionType(action).setTimestamp(timestamp).build();

            assertThat(UserActionProto.parseFrom(original.toByteArray())).isEqualTo(original);
        }
    }

    @Test
    void shouldRoundTripRecommendationMessagesAndEmptyInteractions() throws Exception {
        UserPredictionsRequestProto predictions = UserPredictionsRequestProto.newBuilder()
                .setUserId(Long.MAX_VALUE).setMaxResults(10).build();
        SimilarEventsRequestProto similar = SimilarEventsRequestProto.newBuilder()
                .setEventId(Long.MAX_VALUE).setUserId(3_000_000_000L).setMaxResults(5).build();
        InteractionsCountRequestProto interactions = InteractionsCountRequestProto.newBuilder()
                .addAllEventId(List.of(3_000_000_000L, Long.MAX_VALUE)).build();
        RecommendedEventProto event = RecommendedEventProto.newBuilder()
                .setEventId(Long.MAX_VALUE).setScore(0.75).build();

        assertThat(UserPredictionsRequestProto.parseFrom(predictions.toByteArray())).isEqualTo(predictions);
        assertThat(SimilarEventsRequestProto.parseFrom(similar.toByteArray())).isEqualTo(similar);
        assertThat(InteractionsCountRequestProto.parseFrom(interactions.toByteArray())).isEqualTo(interactions);
        assertThat(RecommendedEventProto.parseFrom(event.toByteArray())).isEqualTo(event);
        assertThat(InteractionsCountRequestProto.parseFrom(new byte[0]).getEventIdList()).isEmpty();
    }

    private void assertFields(Descriptors.Descriptor descriptor, Tuple... fields) {
        assertThat(descriptor.getFile().getPackage()).isEqualTo("stats.message");
        assertThat(descriptor.getFields())
                .extracting(Descriptors.FieldDescriptor::getName, Descriptors.FieldDescriptor::getNumber,
                        Descriptors.FieldDescriptor::getType, Descriptors.FieldDescriptor::isRepeated)
                .containsExactly(fields);
    }

    private void assertMethod(Descriptors.ServiceDescriptor service, String name,
                              String inputType, String outputType, boolean serverStreaming) {
        Descriptors.MethodDescriptor method = service.findMethodByName(name);

        assertThat(method).isNotNull();
        assertThat(method.getInputType().getFullName()).isEqualTo(inputType);
        assertThat(method.getOutputType().getFullName()).isEqualTo(outputType);
        assertThat(method.isClientStreaming()).isFalse();
        assertThat(method.isServerStreaming()).isEqualTo(serverStreaming);
    }
}
