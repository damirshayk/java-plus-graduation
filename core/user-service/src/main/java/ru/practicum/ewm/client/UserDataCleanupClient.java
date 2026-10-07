package ru.practicum.ewm.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "event-service")
public interface UserDataCleanupClient {

    @DeleteMapping("/internal/users/{id}/data")
    void deleteUserData(@PathVariable("id") Long id);
}
