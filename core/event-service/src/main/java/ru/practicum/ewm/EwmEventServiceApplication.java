package ru.practicum.ewm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import ru.practicum.ewm.client.RequestClient;
import ru.practicum.ewm.client.user.UserClient;
import ru.practicum.ewm.client.user.UserDirectory;
import ru.practicum.ewm.service.EventDisplayProperties;

@SpringBootApplication
@EnableConfigurationProperties(EventDisplayProperties.class)
@EnableFeignClients(clients = {UserClient.class, RequestClient.class})
@Import(UserDirectory.class)
public class EwmEventServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EwmEventServiceApplication.class, args);
    }
}
