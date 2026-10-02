package io.github.jozephzemambo.jobradar.config;

import io.github.jozephzemambo.jobradar.http.HttpFetcher;
import io.github.jozephzemambo.jobradar.source.ashby.AshbySource;
import io.github.jozephzemambo.jobradar.source.greenhouse.GreenhouseSource;
import io.github.jozephzemambo.jobradar.source.lever.LeverSource;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Explicit wiring for the HTTP stack and the ATS sources. Sources are plain classes (no annotations) so tests can
 * construct them directly against a WireMock URL; this class is the only place that knows the production URLs.
 */
@Configuration
@EnableConfigurationProperties(JobRadarProperties.class)
public class SourceConfig {

    @Bean
    HttpClient httpClient(JobRadarProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(props.http().connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Bean
    HttpFetcher httpFetcher(HttpClient httpClient, JobRadarProperties props) {
        return new HttpFetcher(httpClient, props.http().userAgent(), props.http().requestTimeout());
    }

    @Bean
    GreenhouseSource greenhouseSource(HttpFetcher http, ObjectMapper mapper, JobRadarProperties props) {
        return new GreenhouseSource(http, mapper, props.sources().greenhouseBaseUrl());
    }

    @Bean
    LeverSource leverSource(HttpFetcher http, ObjectMapper mapper, JobRadarProperties props) {
        return new LeverSource(http, mapper, props.sources().leverBaseUrl());
    }

    @Bean
    AshbySource ashbySource(HttpFetcher http, ObjectMapper mapper, JobRadarProperties props) {
        return new AshbySource(http, mapper, props.sources().ashbyBaseUrl());
    }
}
