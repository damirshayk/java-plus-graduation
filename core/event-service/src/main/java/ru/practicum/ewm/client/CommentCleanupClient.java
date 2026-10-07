package ru.practicum.ewm.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(name = "comment-service")
public interface CommentCleanupClient {
    @PostMapping("/internal/users/{userId}/comments/cleanup")
    void cleanup(@PathVariable("userId") Long userId, @RequestBody List<Long> eventIds);
}
