package com.bobfull.payment.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "portone")
public record PortOneProperties(
        @NotBlank String apiSecret,
        @NotBlank String storeId,
        @NotBlank String webhookSecret
) {
}
