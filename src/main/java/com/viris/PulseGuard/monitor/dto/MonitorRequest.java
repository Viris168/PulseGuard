package com.viris.PulseGuard.monitor.dto;

import jakarta.validation.constraints.*;

public record MonitorRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 100)
        String name,

        @NotBlank(message = "URL is required")
        @Size(max = 2048)
        @Pattern(regexp = "^https?://.+", message = "URL must start with http:// or https://")
        String url,

        @NotBlank(message = "Method is required")
        @Pattern(regexp = "GET|POST|PUT|HEAD", message = "Method must be GET, POST, PUT or HEAD")
        String method,

        @NotNull(message = "Expected status code is required")
        @Min(100) @Max(599)
        Integer expectedStatus,

        @NotNull(message = "Interval is required")
        @Min(value = 60, message = "Interval must be at least 60 seconds")
        Integer intervalSeconds,

        @NotNull(message = "Timeout is required")
        @Min(value = 1000, message = "Timeout must be at least 1000 ms")
        @Max(value = 30000, message = "Timeout must not exceed 30000 ms")
        Integer timeoutMs
) {
}