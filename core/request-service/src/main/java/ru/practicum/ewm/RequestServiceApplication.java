package ru.practicum.ewm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import ru.practicum.ewm.client.event.EventClient;
import ru.practicum.ewm.client.event.EventDirectory;
import ru.practicum.ewm.client.user.UserClient;
import ru.practicum.ewm.client.user.UserDirectory;

@SpringBootApplication
@EnableFeignClients(clients = {UserClient.class, EventClient.class})
@Import({UserDirectory.class, EventDirectory.class})
public class RequestServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RequestServiceApplication.class, args);
    }
}
