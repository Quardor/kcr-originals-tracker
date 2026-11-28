package ru.quard0r.kcrbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ForkJoinPool;

@Configuration
public class AiConfig {

    private final long connectTimeoutMillis;
    private final long readTimeoutMillis;

    public AiConfig(@Value("${app.ml.connect-timeout-ms}") long connectTimeoutMillis,
                    @Value("${app.ml.read-timeout-ms}") long readTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Bean
    public HttpClient mlHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
    }

    @Bean
    public RestClient mlRestClient(HttpClient mlHttpClient) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(mlHttpClient, ForkJoinPool.commonPool());
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
