package com.agentyard.platform.task;

import com.agentyard.platform.auth.*;
import com.agentyard.platform.auth.AuthModels.*;
import com.agentyard.platform.catalog.*;
import com.agentyard.platform.integration.InternalRuntimeAuth;
import com.agentyard.platform.shared.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/** Only the identity provider is substituted. Controllers, authorization and persistence are production beans. */
@TestConfiguration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
    SecurityFilterAutoConfiguration.class})
@Import({CollaborationController.class, CollaborationService.class, TaskCommandService.class,
    TaskRuntimeResolver.class, TaskExecutionService.class, InternalTaskExecutionController.class,
    InternalTaskSessionController.class, TaskSessionBridge.class, InternalRuntimeAuth.class,
    AuthService.class, PlatformAuthorization.class, ApiExceptionHandler.class})
class TaskE2eServer {
    @Bean CatalogRepository catalog(DSLContext db, ObjectMapper mapper) { return new JooqCatalogRepository(db, mapper); }

    @Bean InMemoryUserDetailsManager users() {
        return new InMemoryUserDetailsManager(List.of("sponsor", "partner", "reviewer", "observer").stream()
            .map(name -> User.withUsername(name).password("{noop}task-e2e").roles("BUSINESS_USER").build()).toList());
    }

    @Bean CurrentUserResolver currentUserResolver() {
        return () -> {
            String name = SecurityContextHolder.getContext().getAuthentication().getName();
            return new PlatformUser(name, name, name, "", AuthSource.EXTERNAL, null, null,
                UserStatus.ACTIVE, Instant.now(), Instant.now(), null, List.of(Role.BUSINESS_USER));
        };
    }

    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.requestMatchers("/api/internal/**").permitAll().anyRequest().authenticated())
            .httpBasic(basic -> {}).build();
    }
}
