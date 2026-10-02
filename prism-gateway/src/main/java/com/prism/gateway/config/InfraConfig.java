package com.prism.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.common.seed.SeedLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpClient;
import java.util.concurrent.Executors;

@Configuration
public class InfraConfig {

    /**
     * One shared HTTP client for all providers. HTTP/1.1 keeps connections simple to reason about
     * (and matches the mocks); its internal work runs on virtual threads.
     */
    @Bean
    HttpClient upstreamHttpClient(PrismProperties props) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.upstream().connectTimeout())
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /**
     * Loads the provided seed files right after Flyway has migrated the schema, and before any bean
     * that reads tenants or prices. Idempotent, so the gateway and the control plane can both run it.
     */
    @Bean
    SeedLoader.Summary seedData(JdbcTemplate jdbc, ObjectMapper mapper, PrismProperties props) {
        return new SeedLoader(jdbc, mapper).load(props.dataDir());
    }
}
