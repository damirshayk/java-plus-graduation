package ru.practicum.ewm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import ru.practicum.ewm.client.UserDataCleanupClient;

@SpringBootApplication
@EnableFeignClients(clients = UserDataCleanupClient.class)
public class EwmUserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EwmUserServiceApplication.class, args);
    }
}
