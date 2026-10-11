package ru.practicum.ewm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.ewm.RequestServiceApplication;
import ru.practicum.ewm.client.event.EventClient;
import ru.practicum.ewm.client.user.UserClient;
import ru.practicum.ewm.dto.event.EventInfoDto;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.model.EventRequestStatusUpdateRequest;
import ru.practicum.ewm.model.EventState;
import ru.practicum.ewm.model.ParticipationRequest;
import ru.practicum.ewm.model.RequestStatus;
import ru.practicum.ewm.model.RequestUpdateStatus;
import ru.practicum.ewm.repository.RequestRepository;
import ru.practicum.ewm.service.impl.JpaConfirmedRequestCounter;
import ru.practicum.ewm.stats.client.CollectorClient;
import ru.practicum.ewm.stats.proto.ActionTypeProto;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = RequestServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:stage3-request-actions;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.open-in-view=false"
})
class Stage3RequestActionsIntegrationTest {
    @Autowired
    private RequestService service;
    @Autowired
    private JpaConfirmedRequestCounter counter;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @SpyBean
    private RequestRepository repository;
    @MockBean
    private UserClient users;
    @MockBean
    private EventClient events;
    @MockBean
    private CollectorClient collector;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM requests");
        jdbc.update("DELETE FROM request_user_guard");
        jdbc.update("DELETE FROM request_event_guard");
        when(users.get(anyLong())).thenAnswer(invocation -> user(invocation.getArgument(0)));
        when(events.get(10L)).thenReturn(new EventInfoDto(10L, 99L, EventState.PUBLISHED, 10, true));
    }

    @Test
    void registerMustObserveCommittedRequestOutsideLocalTransaction() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(requestStatus(5L, 10L)).isEqualTo("PENDING");
            return null;
        }).when(collector).collect(5L, 10L, ActionTypeProto.ACTION_REGISTER);

        var result = service.addParticipationRequest(5L, 10L);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getRequester()).isEqualTo(5L);
        assertThat(result.getEvent()).isEqualTo(10L);
        assertThat(result.getStatus()).isEqualTo("PENDING");
        verify(collector).collect(5L, 10L, ActionTypeProto.ACTION_REGISTER);
        verifyNoMoreInteractions(collector);
    }

    @Test
    void registerMustSuspendCallerTransactionAndSurviveItsLaterRollback() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(requestStatus(5L, 10L)).isEqualTo("PENDING");
            return null;
        }).when(collector).collect(5L, 10L, ActionTypeProto.ACTION_REGISTER);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            service.addParticipationRequest(5L, 10L);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            throw new IllegalStateException("Откат внешней операции");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Откат внешней операции");

        assertThat(requestStatus(5L, 10L)).isEqualTo("PENDING");
        verify(collector).collect(5L, 10L, ActionTypeProto.ACTION_REGISTER);
    }

    @Test
    void failedSaveMustRollBackGuardsAndNotPublishRegister() {
        doThrow(new DataIntegrityViolationException("Ошибка сохранения заявки"))
                .when(repository).save(any(ParticipationRequest.class));

        assertThatThrownBy(() -> service.addParticipationRequest(5L, 10L))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM requests", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_user_guard", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_event_guard", Long.class)).isZero();
        verifyNoInteractions(collector);
    }

    @Test
    void rejectedDuplicateMustNotPublishAnotherRegister() {
        service.addParticipationRequest(5L, 10L);
        clearInvocations(collector);

        assertThatThrownBy(() -> service.addParticipationRequest(5L, 10L)).isInstanceOf(ConflictException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM requests", Long.class)).isEqualTo(1L);
        verifyNoInteractions(collector);
    }

    @Test
    void unpublishedEventMustNotSaveOrPublishRegister() {
        when(events.get(10L)).thenReturn(new EventInfoDto(10L, 99L, EventState.PENDING, 10, true));

        assertThatThrownBy(() -> service.addParticipationRequest(5L, 10L)).isInstanceOf(ConflictException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM requests", Long.class)).isZero();
        verifyNoInteractions(collector);
    }

    @Test
    void cancellationMustNotPublishRegisterAndMustRemoveConfirmedAttendance() {
        when(events.get(10L)).thenReturn(new EventInfoDto(10L, 99L, EventState.PUBLISHED, 0, false));
        var request = service.addParticipationRequest(5L, 10L);
        assertThat(counter.hasConfirmedRequest(5L, 10L)).isTrue();
        clearInvocations(collector);

        assertThat(service.cancelRequest(5L, request.getId()).getStatus()).isEqualTo("CANCELED");

        assertThat(counter.hasConfirmedRequest(5L, 10L)).isFalse();
        verifyNoInteractions(collector);
    }

    @Test
    void moderationRejectionMustNotPublishRegisterOrConfirmAttendance() {
        var request = service.addParticipationRequest(5L, 10L);
        clearInvocations(collector);
        EventRequestStatusUpdateRequest update = new EventRequestStatusUpdateRequest();
        update.setRequestIds(List.of(request.getId()));
        update.setStatus(RequestUpdateStatus.REJECTED);

        var result = service.changeRequestStatus(99L, 10L, update);

        assertThat(result.getRejectedRequests()).extracting(dto -> dto.getStatus()).containsExactly("REJECTED");
        assertThat(counter.hasConfirmedRequest(5L, 10L)).isFalse();
        verifyNoInteractions(collector);
    }

    @ParameterizedTest
    @EnumSource(RequestStatus.class)
    void onlyConfirmedRequestForExactUserAndEventMustAuthorizeAttendance(RequestStatus status) {
        jdbc.update("""
                INSERT INTO requests(event_id, requester_id, status, created)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, 10L, 5L, status.name());

        assertThat(counter.hasConfirmedRequest(5L, 10L)).isEqualTo(status == RequestStatus.CONFIRMED);
        assertThat(counter.hasConfirmedRequest(6L, 10L)).isFalse();
        assertThat(counter.hasConfirmedRequest(5L, 20L)).isFalse();

        verifyNoInteractions(collector);
    }

    private String requestStatus(long userId, long eventId) {
        return jdbc.queryForObject("SELECT status FROM requests WHERE requester_id = ? AND event_id = ?",
                String.class, userId, eventId);
    }

    private UserShortDto user(long id) {
        UserShortDto user = new UserShortDto();
        user.setId(id);
        user.setName("Пользователь " + id);
        return user;
    }
}
