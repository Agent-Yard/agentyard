package com.example.lynxus.extensiontemplate.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "lynxus.extension-template")
public record ExtensionTemplateProperties(
    @NotBlank String internalAuthToken,
    @NotBlank String registrationId,
    @NotBlank String channelProviderType,
    @NotBlank String toolConnectorType,
    @NotNull
    URI lynxusChannelGatewayBaseUrl
) {}
