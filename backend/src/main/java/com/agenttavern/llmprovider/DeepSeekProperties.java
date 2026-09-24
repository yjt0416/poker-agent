package com.agenttavern.llmprovider;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;

public record DeepSeekProperties(URI baseUrl, String apiKey, String model, Duration timeout,
                                 int maxOutputTokens, int maxRequestsPerHour) {
    public DeepSeekProperties {
        requireNonNull(baseUrl, "baseUrl");
        requireNonNull(apiKey, "apiKey");
        requireNonNull(model, "model");
        requireNonNull(timeout, "timeout");
        if (apiKey.isBlank() || model.isBlank()) throw new IllegalArgumentException("DeepSeek key and model are required");
        if (!"https".equalsIgnoreCase(baseUrl.getScheme())
                && !("http".equalsIgnoreCase(baseUrl.getScheme()) && baseUrl.getHost() != null
                && (baseUrl.getHost().equals("127.0.0.1") || baseUrl.getHost().equals("localhost")))) {
            throw new IllegalArgumentException("DeepSeek base URL must use HTTPS (HTTP is test-loopback only)");
        }
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        if (maxOutputTokens < 1 || maxOutputTokens > 4096) {
            throw new IllegalArgumentException("maxOutputTokens must be between 1 and 4096");
        }
        if (maxRequestsPerHour < 1) throw new IllegalArgumentException("maxRequestsPerHour must be positive");
    }

    public DeepSeekProperties(URI baseUrl, String apiKey, String model, Duration timeout) {
        this(baseUrl, apiKey, model, timeout, 512, 120);
    }

    public static DeepSeekProperties production(String apiKey) {
        return new DeepSeekProperties(URI.create("https://api.deepseek.com"), apiKey,
                "deepseek-flash", Duration.ofSeconds(20));
    }

    URI chatCompletionsUri() {
        return URI.create(baseUrl.toString().replaceAll("/+$", "") + "/chat/completions");
    }

    @Override public String toString() {
        return "DeepSeekProperties[baseUrl=" + baseUrl + ", apiKey=<redacted>, model=" + model
                + ", timeout=" + timeout + ", maxOutputTokens=" + maxOutputTokens
                + ", maxRequestsPerHour=" + maxRequestsPerHour + "]";
    }
}
