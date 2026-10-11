package ru.practicum.ewm.service;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.ewm.EwmEventServiceApplication;
import ru.practicum.ewm.client.RequestClient;
import ru.practicum.ewm.client.user.UserClient;
import ru.practicum.ewm.dto.compilation.CompilationDto;
import ru.practicum.ewm.dto.compilation.NewCompilationDto;
import ru.practicum.ewm.dto.compilation.UpdateCompilationRequest;
import ru.practicum.ewm.dto.event.EventShortDto;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.stats.client.AnalyzerClient;
import ru.practicum.ewm.stats.client.CollectorClient;
import ru.practicum.ewm.stats.proto.RecommendedEventProto;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = EwmEventServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:stage3-ratings;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true", "spring.jpa.open-in-view=false",
        "ewm.display.retry.backoff-ms=1"
})
class Stage3RatingIntegrationTest {
    @Autowired
    private EventService events;
    @Autowired
    private CompilationService compilations;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockBean
    private UserClient users;
    @MockBean
    private RequestClient requests;
    @MockBean
    private AnalyzerClient analyzer;
    @MockBean
    private CollectorClient collector;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM compilations_events");
        jdbc.update("DELETE FROM compilations");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM categories");
        LongStream.rangeClosed(1, 51).forEach(id -> {
            jdbc.update("INSERT INTO categories(id, name) VALUES (?, ?)", id, "Категория " + id);
            jdbc.update("""
                    INSERT INTO events(id, annotation, category_id, created_on, description, event_date, initiator_id,
                                       location_lat, location_lon, paid, participant_limit, request_moderation, state, title)
                    VALUES (?, 'Аннотация', ?, CURRENT_TIMESTAMP, 'Описание',
                            DATEADD('DAY', 3, CURRENT_TIMESTAMP), ?, 1, 1, false, 0, true, 'PUBLISHED', ?)
                    """, id * 10, id, id % 2 + 1, "Событие " + id);
        });
        jdbc.update("INSERT INTO compilations(id, title, is_pinned) VALUES (100, 'Первая', false), (200, 'Вторая', false)");
        jdbc.update("""
                INSERT INTO compilations_events(compilation_id, event_id)
                VALUES (100, 10), (100, 20), (200, 20), (200, 30)
                """);
        when(users.batch(anyList())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            List<Long> ids = invocation.getArgument(0);
            return ids.stream().map(this::user).toList();
        });
        when(requests.confirmedCounts(anyList())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            return Map.of();
        });
        when(analyzer.ratings(anyList())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            List<Long> ids = invocation.getArgument(0);
            return ids.stream().collect(Collectors.toMap(id -> id, id -> id / 100.0));
        });
    }

    @Test
    void recommendationsMustLoadCategoriesWithOneQueryForOneAndFiftyOneEvents() {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        when(analyzer.recommendations(5L, 51)).thenAnswer(invocation -> {
            assertOutsideTransaction();
            return recommendations(List.of(10L));
        });
        statistics.clear();

        assertThat(events.getRecommendations(5L, 51)).extracting(EventShortDto::getId).containsExactly(10L);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
        clearInvocations(users, requests, analyzer);
        List<Long> ids = LongStream.rangeClosed(1, 51).map(id -> id * 10).boxed().toList();
        when(analyzer.recommendations(5L, 51)).thenAnswer(invocation -> {
            assertOutsideTransaction();
            return recommendations(ids);
        });
        statistics.clear();

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            List<EventShortDto> result = events.getRecommendations(5L, 51);
            assertThat(result).extracting(EventShortDto::getId).containsExactlyElementsOf(ids);
            assertThat(result).extracting(dto -> dto.getCategory().getId())
                    .containsExactlyElementsOf(LongStream.rangeClosed(1, 51).boxed().toList());
        });

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
        verify(analyzer).recommendations(5L, 51);
        verify(analyzer).ratings(ids);
        verifyNoMoreInteractions(analyzer);
        verify(requests).confirmedCounts(ids);
        verifyNoMoreInteractions(requests);
        verify(users).batch(List.of(2L, 1L));
        verifyNoMoreInteractions(users);
        verifyNoInteractions(collector);
    }

    @Test
    void compilationListMustRequestOneRatingBatchForSharedEvents() {
        List<CompilationDto> result = compilations.getCompilations(null, 0, 10);

        assertThat(result).extracting(CompilationDto::getId).containsExactly(100L, 200L);
        assertThat(result.getFirst().getEvents()).extracting(EventShortDto::getId).containsExactly(10L, 20L);
        assertThat(result.getFirst().getEvents()).extracting(EventShortDto::getRating).containsExactly(0.1, 0.2);
        assertThat(result.get(1).getEvents()).extracting(EventShortDto::getId).containsExactly(20L, 30L);
        assertThat(result.get(1).getEvents()).extracting(EventShortDto::getRating).containsExactly(0.2, 0.3);
        verify(analyzer).ratings(List.of(10L, 20L, 30L));
        verifyNoMoreInteractions(analyzer);
        verify(users).batch(argThat(ids -> ids.size() == 2 && Set.copyOf(ids).equals(Set.of(1L, 2L))));
        verifyNoMoreInteractions(users);
        verifyNoInteractions(requests, collector);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compilationWritesMustEnrichRatingsBeforeOwnTransactionAndSuspendCaller(boolean create) {
        when(analyzer.ratings(List.of(10L, 20L))).thenAnswer(invocation -> {
            assertOutsideTransaction();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM compilations", Long.class)).isEqualTo(2L);
            assertThat(jdbc.queryForObject("SELECT title FROM compilations WHERE id = 100", String.class))
                    .isEqualTo("Первая");
            return Map.of(10L, 0.4, 20L, 3.4);
        });

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            CompilationDto result = writeCompilation(create);
            assertThat(result.getTitle()).isEqualTo("Изменённая");
            assertThat(result.getEvents()).extracting(EventShortDto::getId).containsExactly(10L, 20L);
            assertThat(result.getEvents()).extracting(EventShortDto::getRating).containsExactly(0.4, 3.4);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });

        verify(analyzer).ratings(List.of(10L, 20L));
        verifyNoMoreInteractions(analyzer);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM compilations WHERE title = ?", Long.class,
                "Изменённая")).isEqualTo(1L);
        verifyNoInteractions(requests, collector);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permanentRatingFailureMustNotStartCompilationWrite(boolean create) {
        when(analyzer.ratings(List.of(10L, 20L)))
                .thenThrow(new IllegalStateException("Некорректный ответ Analyzer"));

        assertThatThrownBy(() -> writeCompilation(create)).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM compilations", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT title FROM compilations WHERE id = 100", String.class))
                .isEqualTo("Первая");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM compilations_events", Long.class)).isEqualTo(4L);
    }

    private CompilationDto writeCompilation(boolean create) {
        if (create) {
            return compilations.create(NewCompilationDto.builder().title("Изменённая").events(Set.of(10L, 20L)).build());
        }
        UpdateCompilationRequest request = new UpdateCompilationRequest();
        request.setTitle("Изменённая");
        return compilations.update(100L, request);
    }

    private List<RecommendedEventProto> recommendations(List<Long> ids) {
        return ids.stream().map(id -> RecommendedEventProto.newBuilder().setEventId(id).setScore(0.5).build()).toList();
    }

    private UserShortDto user(long id) {
        UserShortDto user = new UserShortDto();
        user.setId(id);
        user.setName("Инициатор " + id);
        return user;
    }

    private void assertOutsideTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
}
