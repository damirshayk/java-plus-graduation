package ru.practicum.ewm.service;

import feign.FeignException;
import feign.codec.DecodeException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.ewm.client.CommentCleanupClient;
import ru.practicum.ewm.client.RequestClient;
import ru.practicum.ewm.exception.ServiceUnavailableException;

import java.util.List;

@Service
public class UserDataCleanupService {
    private final UserDataGuard userDataGuard;
    private final JdbcTemplate jdbc;
    private final CommentCleanupClient comments;
    private final RequestClient requests;
    private final TransactionTemplate localTransaction;
    private final TransactionTemplate withoutTransaction;

    public UserDataCleanupService(UserDataGuard userDataGuard, JdbcTemplate jdbc, CommentCleanupClient comments,
                                  RequestClient requests,
                                  PlatformTransactionManager transactionManager) {
        this.userDataGuard = userDataGuard;
        this.jdbc = jdbc;
        this.comments = comments;
        this.requests = requests;
        this.localTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public void deleteUserData(Long userId) {
        withoutTransaction.executeWithoutResult(ignored -> {
            List<Long> eventIds = localTransaction.execute(status -> {
                userDataGuard.markDeleting(userId);
                return jdbc.queryForList("SELECT id FROM events WHERE initiator_id = ? ORDER BY id",
                        Long.class, userId);
            });
            try {
                comments.cleanup(userId, eventIds);
            } catch (FeignException exception) {
                if (!(exception instanceof DecodeException) && (exception.status() == -1 || exception.status() >= 500)) {
                    throw new ServiceUnavailableException("Сервис комментариев временно недоступен");
                }
                throw exception;
            }
            try {
                requests.cleanup(userId, eventIds);
            } catch (FeignException exception) {
                if (!(exception instanceof DecodeException) && (exception.status() == -1 || exception.status() >= 500)) {
                    throw new ServiceUnavailableException("Сервис заявок временно недоступен");
                }
                throw exception;
            }
            localTransaction.executeWithoutResult(status -> {
                userDataGuard.markDeleting(userId);
                jdbc.update("DELETE FROM events WHERE initiator_id = ?", userId);
            });
        });
    }
}
