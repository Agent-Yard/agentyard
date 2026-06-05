package com.agentyard.platform.extension;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class ExtensionRegistryValidationSecurityConfiguration {
    @Bean
    @Order(0)
    SecurityFilterChain extensionRegistryInternalSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/internal/extension-registry/**")
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
            .build();
    }
}
