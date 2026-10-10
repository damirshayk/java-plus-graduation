package ru.practicum.ewm.stats.aggregator.service;

import ru.practicum.ewm.stats.avro.EventSimilarityAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;
import ru.practicum.ewm.stats.serialization.ActionWeights;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Класс для вычисления сходства событий на основе действий пользователей.
 */
public class SimilarityCalculator {

    private final ActionWeights weights;
    private final Map<Long, Map<Long, Double>> eventWeights = new LinkedHashMap<>();
    private final Map<Long, Double> eventSums = new HashMap<>();
    private final Map<EventPair, Double> pairMinSums = new HashMap<>();
    private long revision;

    /**
     * Создает калькулятор сходства событий с заданными весами действий пользователей.
     *
     * @param weights веса действий пользователей
     */
    public SimilarityCalculator(ActionWeights weights) {
        this.weights = Objects.requireNonNull(weights);
    }

    /**
     * Подготавливает обновление сумм и сходств, не изменяя состояние калькулятора.
     * Сходство пары: sum(min(wA, wB)) / sqrt(sum(wA) * sum(wB)).
     *
     * @param action действие пользователя
     * @return подготовленное обновление калькулятора сходства событий
     */
    public PreparedUpdate prepare(UserActionAvro action) {
        long eventId = action.getEventId();
        long userId = action.getUserId();
        double newWeight = weights.weight(action.getActionType());
        Map<Long, Double> userWeights = eventWeights.get(eventId);
        double oldWeight = userWeights == null ? 0.0 : userWeights.getOrDefault(userId, 0.0);
        double oldSum = eventSums.getOrDefault(eventId, 0.0);

        // Повторы и более слабые действия не меняют максимальный вес взаимодействия.
        if (userWeights != null && newWeight <= oldWeight) {
            return new PreparedUpdate(this, revision, userId, eventId, oldWeight, oldSum,
                    Map.of(), List.of(), false);
        }

        double newSum = oldSum + (newWeight - oldWeight);
        Map<EventPair, Double> newPairMinSums = new HashMap<>();
        List<EventSimilarityAvro> similarities = new ArrayList<>();
        // Пересчитываем пары только с другими событиями из истории текущего пользователя.
        for (var entry : eventWeights.entrySet()) {
            long otherEvent = entry.getKey();
            if (otherEvent == eventId) {
                continue;
            }
            double otherWeight = entry.getValue().getOrDefault(userId, 0.0);
            if (otherWeight == 0.0) {
                continue;
            }
            EventPair pair = EventPair.of(eventId, otherEvent);
            // Обновляем сумму минимумов только на разницу вклада текущего пользователя.
            double newMinSum = pairMinSums.getOrDefault(pair, 0.0)
                    + (Math.min(newWeight, otherWeight) - Math.min(oldWeight, otherWeight));
            newPairMinSums.put(pair, newMinSum);
            if (newMinSum == 0.0) {
                continue;
            }

            // В истории пользователя знаменатель может измениться без прироста суммы минимумов.
            double denominator = Math.sqrt(newSum) * Math.sqrt(eventSums.get(otherEvent));
            double score = denominator == 0.0 ? 0.0 : Math.min(1.0, newMinSum / denominator);
            similarities.add(new EventSimilarityAvro(pair.eventA(), pair.eventB(), score, action.getTimestamp()));
        }
        return new PreparedUpdate(this, revision, userId, eventId, newWeight, newSum,
                newPairMinSums, similarities, true);
    }

    /**
     * Применяет подготовленное обновление калькулятора сходства событий.
     *
     * @param update подготовленное обновление калькулятора сходства событий
     */
    public void apply(PreparedUpdate update) {
        if (update.owner != this) {
            throw new IllegalArgumentException("Обновление подготовлено другим калькулятором");
        }
        if (update.revision != revision) {
            throw new IllegalStateException("Подготовленное обновление устарело");
        }
        if (!update.changed) {
            return;
        }
        eventWeights.computeIfAbsent(update.eventId, ignored -> new HashMap<>())
                .put(update.userId, update.newWeight);
        eventSums.put(update.eventId, update.newSum);
        pairMinSums.putAll(update.newPairMinSums);
        revision++;
    }

    /**
     * Сбрасывает состояние калькулятора сходства событий.
     */
    public void reset() {
        eventWeights.clear();
        eventSums.clear();
        pairMinSums.clear();
        revision++;
    }

    /**
     * Подготовленное обновление калькулятора сходства событий.
     */
    public static final class PreparedUpdate {

        private final SimilarityCalculator owner;
        private final long revision;
        private final long userId;
        private final long eventId;
        private final double newWeight;
        private final double newSum;
        private final Map<EventPair, Double> newPairMinSums;
        private final List<EventSimilarityAvro> similarities;
        private final boolean changed;

        private PreparedUpdate(SimilarityCalculator owner, long revision, long userId, long eventId,
                               double newWeight, double newSum, Map<EventPair, Double> newPairMinSums,
                               List<EventSimilarityAvro> similarities, boolean changed) {
            this.owner = owner;
            this.revision = revision;
            this.userId = userId;
            this.eventId = eventId;
            this.newWeight = newWeight;
            this.newSum = newSum;
            this.newPairMinSums = Map.copyOf(newPairMinSums);
            this.similarities = List.copyOf(similarities);
            this.changed = changed;
        }

        /**
         * Возвращает рассчитанные сходства событий из подготовленного обновления.
         *
         * @return список сходств событий
         */
        public List<EventSimilarityAvro> similarities() {
            return similarities.stream().map(value -> new EventSimilarityAvro(value.getEventA(),
                    value.getEventB(), value.getScore(), value.getTimestamp())).toList();
        }
    }

    // Фиксированный порядок ID даёт один ключ для пар (A, B) и (B, A).
    private record EventPair(long eventA, long eventB) {

        static EventPair of(long first, long second) {
            return first < second ? new EventPair(first, second) : new EventPair(second, first);
        }
    }
}
