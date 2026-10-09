package ru.practicum.ewm.client.user;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import ru.practicum.ewm.dto.user.UserShortDto;

import java.util.List;

@FeignClient(name = "user-service")
public interface UserClient {

    @GetMapping("/internal/users/{id}")
    UserShortDto get(@PathVariable("id") Long id);

    @PostMapping("/internal/users/lookup")
    List<UserShortDto> batch(@RequestBody List<Long> ids);
}
