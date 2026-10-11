package ru.practicum.ewm.stats.aggregator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.cloud.netflix.eureka.serviceregistry.EurekaAutoServiceRegistration;
import org.springframework.cloud.netflix.eureka.serviceregistry.EurekaRegistration;

import static org.assertj.core.api.Assertions.assertThat;

class AggregatorRegistrationTest {

    @Test
    void shouldRegisterActualRandomHttpPortWithoutAnExternalEureka() {
        SpringApplication application = new SpringApplication(AggregatorApplication.class);
        assertThat(application.getWebApplicationType()).isEqualTo(WebApplicationType.SERVLET);

        try (var context = application.run("--spring.profiles.active=test",
                "--spring.cloud.config.enabled=false", "--server.port=0",
                "--eureka.client.register-with-eureka=false", "--eureka.client.fetch-registry=false",
                "--eureka.client.refresh.enable=false", "--spring.kafka.listener.auto-startup=false",
                "--spring.kafka.consumer.group-id=aggregator-registration-test",
                "--stats.kafka.topics.user-actions=registration-actions",
                "--stats.kafka.topics.events-similarity=registration-similarities",
                "--stats.kafka.send-timeout-ms=1000", "--stats.kafka.retry-backoff-ms=10",
                "--stats.weights.view=0.4", "--stats.weights.register=0.8", "--stats.weights.like=1.0")) {
            int httpPort = ((WebServerApplicationContext) context).getWebServer().getPort();
            EurekaRegistration registration = context.getBean(EurekaRegistration.class);

            assertThat(httpPort).isPositive();
            assertThat(registration.getNonSecurePort()).isEqualTo(httpPort);
            assertThat(context.getBean(EurekaAutoServiceRegistration.class).isRunning()).isTrue();
        }
    }
}
