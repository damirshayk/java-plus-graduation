package ru.practicum.ewm.service;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.codec.DecodeException;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.ewm.RequestServiceApplication;
import ru.practicum.ewm.client.event.EventClient;
import ru.practicum.ewm.client.user.UserClient;
import ru.practicum.ewm.dto.event.EventInfoDto;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ServiceUnavailableException;
import ru.practicum.ewm.model.EventRequestStatusUpdateRequest;
import ru.practicum.ewm.model.EventState;
import ru.practicum.ewm.model.RequestUpdateStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = RequestServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:request-protocol;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true", "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class RequestProtocolIntegrationTest {
    @Autowired
    private RequestService service;
    @Autowired
    private RequestDataCleanupService cleanup;
    @Autowired
    private RequestDataGuard guard;
    @SpyBean
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @MockBean
    private UserClient users;
    @MockBean
    private EventClient events;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM requests");
        jdbc.update("DELETE FROM request_user_guard");
        jdbc.update("DELETE FROM request_event_guard");
        jdbc.update("""
                INSERT INTO requests(id, event_id, requester_id, status, created)
                VALUES (100, 10, 1, 'PENDING', CURRENT_TIMESTAMP),
                       (200, 10, 2, 'PENDING', CURRENT_TIMESTAMP),
                       (300, 20, 1, 'PENDING', CURRENT_TIMESTAMP)
                """);
        when(users.get(anyLong())).thenAnswer(invocation -> user(invocation.getArgument(0)));
        when(events.get(10L)).thenReturn(info(2, true));
    }

    @Test
    void remoteUserAndEventCallsMustSuspendCallerTransactionBeforeCreate() {
        when(users.get(3L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return user(3L);
        });
        when(events.get(10L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return info(2, true);
        });
        var result = new TransactionTemplate(transactionManager)
                .execute(tx -> service.addParticipationRequest(3L, 10L));
        assertThat(result.getEvent()).isEqualTo(10L);
        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getCreated()).isNotNull();
        verify(users).get(3L);
        verify(events).get(10L);
    }

    @Test
    void zeroLimitAndDisabledModerationMustAutomaticallyConfirm() {
        when(events.get(10L)).thenReturn(info(0, true));
        assertThat(service.addParticipationRequest(3L, 10L).getStatus()).isEqualTo("CONFIRMED");
        when(events.get(10L)).thenReturn(info(2, false));
        assertThat(service.addParticipationRequest(4L, 10L).getStatus()).isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> service.addParticipationRequest(5L, 10L)).isInstanceOf(ConflictException.class);
    }

    @Test
    void malformedCriticalPolicyMustFailBeforeAnyGuardOrWrite() {
        for (EventInfoDto invalid : new EventInfoDto[]{null,
                new EventInfoDto(null, 99L, EventState.PUBLISHED, 2, true),
                new EventInfoDto(11L, 99L, EventState.PUBLISHED, 2, true),
                new EventInfoDto(10L, null, EventState.PUBLISHED, 2, true),
                new EventInfoDto(10L, 0L, EventState.PUBLISHED, 2, true),
                new EventInfoDto(10L, 99L, null, 2, true),
                new EventInfoDto(10L, 99L, EventState.PUBLISHED, null, true),
                new EventInfoDto(10L, 99L, EventState.PUBLISHED, -1, true),
                new EventInfoDto(10L, 99L, EventState.PUBLISHED, 2, null)}) {
            when(events.get(10L)).thenReturn(invalid);
            assertThatThrownBy(() -> service.addParticipationRequest(3L, 10L)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_user_guard", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM requests", Long.class)).isEqualTo(3);
    }

    @Test
    void duplicateCanceledSelfAndUnpublishedRequestsMustKeepConflict() {
        jdbc.update("UPDATE requests SET status = 'CANCELED' WHERE id = 100");
        assertThatThrownBy(() -> service.addParticipationRequest(1L, 10L)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.addParticipationRequest(99L, 10L)).isInstanceOf(ConflictException.class);
        when(events.get(10L)).thenReturn(new EventInfoDto(10L, 99L, EventState.PENDING, 2, true));
        assertThatThrownBy(() -> service.addParticipationRequest(3L, 10L)).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM requests", Long.class)).isEqualTo(3);
    }

    @Test
    void earlyModerationBranchMustReturnSelectedRequestsWithoutChangingStatuses() {
        jdbc.update("UPDATE requests SET status = 'CANCELED' WHERE id = 100");
        jdbc.update("UPDATE requests SET status = 'REJECTED' WHERE id = 200");
        for (EventInfoDto policy : List.of(info(0, true), info(1, false))) {
            when(events.get(10L)).thenReturn(policy);
            var result = service.changeRequestStatus(99L, 10L, update(List.of(200L, 100L, 200L), RequestUpdateStatus.REJECTED));
            assertThat(result.getConfirmedRequests()).extracting(request -> request.getId()).containsExactly(100L, 200L);
            assertThat(result.getConfirmedRequests()).extracting(request -> request.getStatus()).containsExactly("CANCELED", "REJECTED");
            assertThat(result.getRejectedRequests()).isEmpty();
        }
        assertThat(statuses()).containsExactly("CANCELED", "REJECTED", "PENDING");
    }

    @Test
    void moderationMustRejectMissingForeignAndNonPendingBeforeChangingAnyRequest() {
        assertThatThrownBy(() -> service.changeRequestStatus(99L, 10L, update(List.of(100L, 300L), RequestUpdateStatus.CONFIRMED)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.changeRequestStatus(99L, 10L, update(List.of(100L, 999L), RequestUpdateStatus.REJECTED)))
                .isInstanceOf(NotFoundException.class);
        jdbc.update("UPDATE requests SET status = 'REJECTED' WHERE id = 200");
        assertThatThrownBy(() -> service.changeRequestStatus(99L, 10L, update(List.of(100L, 200L), RequestUpdateStatus.CONFIRMED)))
                .isInstanceOf(ConflictException.class);
        assertThat(statuses()).containsExactly("PENDING", "REJECTED", "PENDING");
    }

    @Test
    void moderatedOverflowMustRejectExcessInStableIdOrderWithoutPublishedRequirement() {
        when(events.get(10L)).thenReturn(new EventInfoDto(10L, 99L, EventState.CANCELED, 1, true));
        var result = service.changeRequestStatus(99L, 10L, update(List.of(200L, 100L, 200L), RequestUpdateStatus.CONFIRMED));
        assertThat(result.getConfirmedRequests()).extracting(request -> request.getId()).containsExactly(100L);
        assertThat(result.getRejectedRequests()).extracting(request -> request.getId()).containsExactly(200L);
        assertThat(statuses()).containsExactly("CONFIRMED", "REJECTED", "PENDING");
    }

    @Test
    void cancellationMustPreserveAuthorRulesWithoutEventHttp() {
        assertThatThrownBy(() -> service.cancelRequest(2L, 100L)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.cancelRequest(1L, 999L)).isInstanceOf(NotFoundException.class);
        assertThat(service.cancelRequest(1L, 100L).getStatus()).isEqualTo("CANCELED");
        assertThat(service.cancelRequest(1L, 100L).getStatus()).isEqualTo("CANCELED");
        verifyNoInteractions(events);
    }

    @Test
    void staleUserAndEventRepliesMustNotBypassPermanentDeletionMarkers() {
        when(users.get(3L)).thenAnswer(invocation -> {
            cleanup.deleteUserData(3L, List.of());
            return user(3L);
        });
        assertThatThrownBy(() -> service.addParticipationRequest(3L, 10L)).isInstanceOf(NotFoundException.class);
        when(events.get(10L)).thenAnswer(invocation -> {
            cleanup.deleteUserData(99L, List.of(10L));
            return info(2, true);
        });
        assertThatThrownBy(() -> service.addParticipationRequest(4L, 10L)).isInstanceOf(NotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM requests", Long.class)).isEqualTo(1);
        doReturn(info(2, true)).when(events).get(10L);
        jdbc.update("INSERT INTO requests(id,event_id,requester_id,status,created) VALUES (400,10,4,'PENDING',CURRENT_TIMESTAMP)");
        assertThatThrownBy(() -> service.cancelRequest(4L, 400L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.changeRequestStatus(99L, 10L, update(List.of(400L), RequestUpdateStatus.CONFIRMED)))
                .isInstanceOf(NotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM requests WHERE id = 400", String.class)).isEqualTo("PENDING");
    }

    @Test
    void listSqlAndRemoteCallsMustRemainFixedForOneAndFiftyOneRequests() {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        jdbc.update("DELETE FROM requests WHERE id <> 100");
        statistics.clear();
        assertThat(service.getEventParticipants(99L, 10L)).hasSize(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        LongStream.rangeClosed(2, 51).forEach(id -> jdbc.update("""
                INSERT INTO requests(event_id, requester_id, status, created) VALUES (10, ?, 'PENDING', CURRENT_TIMESTAMP)
                """, id));
        statistics.clear();
        assertThat(service.getEventParticipants(99L, 10L)).hasSize(51);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        verify(users, times(2)).get(99L);
        verify(events, times(2)).get(10L);
        statistics.clear();
        assertThat(service.getUserRequests(1L)).hasSize(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        verify(users).get(1L);
        verifyNoMoreInteractions(users, events);
    }

    @Test
    void userOutageMustReadOnlyOwnLocalRequestsWithOneSqlStatement() {
        doThrow(failure(503)).when(users).get(1L);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var result = assertDoesNotThrow(() -> service.getUserRequests(1L));

        assertThat(result).extracting(request -> request.getId()).containsExactly(100L, 300L);
        assertThat(result).extracting(request -> request.getRequester()).containsOnly(1L);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        verify(users).get(1L);
        verifyNoInteractions(events);
    }

    @Test
    void userOutageWithoutOwnRowsMustReturn503InsteadOfEmptySuccessfulList() throws Exception {
        doThrow(failure(503)).when(users).get(999L);

        mvc.perform(get("/users/999/requests")).andExpect(status().isServiceUnavailable());

        verify(users).get(999L);
        verifyNoInteractions(events);
    }

    @Test
    void participantReadDuringUserOutageMustKeepRealOwnerCheckBeforeSql() {
        doThrow(failure(503)).when(users).get(99L);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        assertThat(assertDoesNotThrow(() -> service.getEventParticipants(99L, 10L))).extracting(request -> request.getId())
                .containsExactly(100L, 200L);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        doThrow(failure(503)).when(users).get(1L);
        statistics.clear();
        assertThatThrownBy(() -> service.getEventParticipants(1L, 10L)).isInstanceOf(ConflictException.class);
        assertThat(statistics.getPrepareStatementCount()).isZero();
        doThrow(failure(503)).when(events).get(10L);
        assertThatThrownBy(() -> service.getEventParticipants(99L, 10L)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    void definitiveUserErrorsAndMalformedSuccessMustNotFallBackToExistingRows() {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        doThrow(failure(404)).when(users).get(1L);
        assertThatThrownBy(() -> service.getUserRequests(1L)).isInstanceOf(NotFoundException.class);
        FeignException forbidden = failure(403);
        doThrow(forbidden).when(users).get(1L);
        assertThatThrownBy(() -> service.getUserRequests(1L)).isSameAs(forbidden);
        DecodeException decode = new DecodeException(200, "Некорректный ответ", request());
        doThrow(decode).when(users).get(1L);
        assertThatThrownBy(() -> service.getUserRequests(1L)).isSameAs(decode);
        doReturn(null).when(users).get(1L);
        assertThatThrownBy(() -> service.getUserRequests(1L)).isInstanceOf(IllegalStateException.class);
        assertThat(statistics.getPrepareStatementCount()).isZero();
        verifyNoInteractions(events);
    }

    @Test
    void networkFailuresMustReturn503BeforeWritesAndDecodeMustRemainUntranslated() throws Exception {
        for (int code : new int[]{-1, 500, 503}) {
            doThrow(failure(code)).when(events).get(10L);
            mvc.perform(post("/users/3/requests").param("eventId", "10")).andExpect(status().isServiceUnavailable());
        }
        DecodeException decode = new DecodeException(200, "Некорректный ответ", request());
        doThrow(decode).when(events).get(10L);
        assertThatThrownBy(() -> service.addParticipationRequest(3L, 10L)).isSameAs(decode);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_user_guard", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM requests", Long.class)).isEqualTo(3);
    }

    @Test
    void guardMustRejectUseOutsideLocalTransaction() {
        assertThatThrownBy(() -> guard.lockForWrite(1L, 10L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard.markDeleting(1L, List.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidModerationIdsMustReturn400BeforeAnySqlRemoteOrWrite() throws Exception {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        clearInvocations(jdbc);
        for (String body : new String[]{
                "{\"requestIds\":[0],\"status\":\"CONFIRMED\"}",
                "{\"requestIds\":[-1],\"status\":\"CONFIRMED\"}",
                "{\"requestIds\":[null],\"status\":\"CONFIRMED\"}",
                "{\"requestIds\":[100,null],\"status\":\"CONFIRMED\"}",
                "{\"requestIds\":null,\"status\":\"CONFIRMED\"}",
                "{\"requestIds\":[],\"status\":\"CONFIRMED\"}",
                "{\"requestIds\":[100],\"status\":null}"}) {
            mvc.perform(patch("/users/99/events/10/requests").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(statistics.getPrepareStatementCount()).isZero();
        verifyNoInteractions(users, events, jdbc);
        assertThat(statuses()).containsExactly("PENDING", "PENDING", "PENDING");
    }

    private List<String> statuses() {
        return jdbc.queryForList("SELECT status FROM requests ORDER BY id", String.class);
    }

    private EventInfoDto info(int limit, boolean moderation) {
        return new EventInfoDto(10L, 99L, EventState.PUBLISHED, limit, moderation);
    }

    private UserShortDto user(Long id) {
        UserShortDto result = new UserShortDto();
        result.setId(id);
        result.setName("Пользователь");
        return result;
    }

    private EventRequestStatusUpdateRequest update(List<Long> ids, RequestUpdateStatus desiredStatus) {
        EventRequestStatusUpdateRequest result = new EventRequestStatusUpdateRequest();
        result.setRequestIds(ids);
        result.setStatus(desiredStatus);
        return result;
    }

    private Request request() {
        return Request.create(Request.HttpMethod.GET, "http://event-service/internal/events/10",
                Map.of(), null, StandardCharsets.UTF_8, null);
    }

    private FeignException failure(int code) {
        return FeignException.errorStatus("EventClient#get",
                Response.builder().status(code).request(request()).headers(Map.of()).build());
    }
}
