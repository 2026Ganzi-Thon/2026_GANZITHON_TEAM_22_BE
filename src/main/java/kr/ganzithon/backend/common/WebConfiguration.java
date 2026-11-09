package kr.ganzithon.backend.common;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class WebConfiguration {
    private final String[] allowedOrigins;

    public WebConfiguration(@Value("${ganzi.cors.allowed-origins}") String configuredOrigins) {
        this.allowedOrigins = parseAllowedOrigins(configuredOrigins).toArray(String[]::new);
    }

    static List<String> parseAllowedOrigins(String configuredOrigins) {
        if (configuredOrigins == null || configuredOrigins.isBlank()) {
            throw new IllegalArgumentException("ganzi.cors.allowed-origins must contain at least one origin");
        }
        List<String> origins = Arrays.stream(configuredOrigins.split(",", -1))
            .map(String::trim).toList();
        for (String origin : origins) {
            try {
                URI uri = new URI(origin);
                if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || origin.contains("*")) {
                    throw new IllegalArgumentException("Invalid CORS origin: " + origin);
                }
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException("Invalid CORS origin: " + origin, ex);
            }
        }
        return origins.stream().distinct().toList();
    }

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("X-Request-ID", "Retry-After", "Allow"));
        config.setAllowCredentials(false);
        config.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
