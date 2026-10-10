package ru.practicum.ewm.stats.collector;

import net.devh.boot.grpc.common.util.GrpcUtils;
import net.devh.boot.grpc.server.config.GrpcServerProperties;
import net.devh.boot.grpc.server.serverfactory.GrpcServerLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.cloud.netflix.eureka.serviceregistry.EurekaAutoServiceRegistration;
import org.springframework.cloud.netflix.eureka.serviceregistry.EurekaRegistration;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

class CollectorRegistrationTest {

    @Test
    void shouldRegisterActualRandomHttpAndGrpcPortsWithoutAnExternalEureka() throws Exception {
        SpringApplication application = new SpringApplication(CollectorApplication.class);
        assertThat(application.getWebApplicationType()).isEqualTo(WebApplicationType.SERVLET);

        try (var context = application.run("--spring.profiles.active=test",
                "--spring.cloud.config.enabled=false", "--server.port=0", "--grpc.server.port=0",
                "--eureka.client.register-with-eureka=false", "--eureka.client.fetch-registry=false",
                "--eureka.client.refresh.enable=false", "--stats.kafka.topics.user-actions=registration-test")) {
            int httpPort = ((WebServerApplicationContext) context).getWebServer().getPort();
            int grpcPort = context.getBean(GrpcServerProperties.class).getPort();
            EurekaRegistration registration = context.getBean(EurekaRegistration.class);

            assertThat(httpPort).isPositive();
            assertThat(registration.getNonSecurePort()).isEqualTo(httpPort);
            assertThat(context.getBean(EurekaAutoServiceRegistration.class).isRunning()).isTrue();
            assertThat(grpcPort).isPositive().isNotEqualTo(httpPort);
            assertThat(context.getBean(GrpcServerLifecycle.class).isRunning()).isTrue();
            assertThat(registration.getInstanceConfig().getMetadataMap())
                    .containsEntry(GrpcUtils.CLOUD_DISCOVERY_METADATA_PORT, Integer.toString(grpcPort));
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", grpcPort), 1000);
                assertThat(socket.isConnected()).isTrue();
            }
        }
    }
}
