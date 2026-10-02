package com.protegey.example;

import com.protegey.sdk.Protegey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProtegeyConfig {

    @Bean
    public Protegey protegey(
        @Value("${protegey.api-key}") String apiKey,
        @Value("${protegey.base-url}") String baseUrl
    ) {
        return new Protegey(apiKey, baseUrl);
    }
}
