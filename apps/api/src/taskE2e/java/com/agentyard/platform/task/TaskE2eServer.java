package com.agentyard.platform.task;

import com.agentyard.platform.auth.*;
import com.agentyard.platform.auth.AuthModels.*;
import com.agentyard.platform.catalog.*;
import com.agentyard.platform.agent.*;
import com.agentyard.platform.channel.*;
import com.agentyard.persistence.agent.AgentStore;
import com.agentyard.persistence.jooqsupport.JooqJsonbSupport;
import com.agentyard.platform.knowledge.*;
import com.agentyard.platform.extension.*;
import com.agentyard.platform.event.PlatformEventService;
import org.springframework.beans.factory.annotation.Value;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration;
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
@EnableTransactionManagement
@ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
    SecurityFilterAutoConfiguration.class,JooqAutoConfiguration.class})
@Import({CollaborationController.class, CollaborationService.class, TaskCommandService.class,
    AgentController.class, InternalAgentController.class, AgentService.class, AgentToolService.class,
    AgentAuthorization.class, CapabilityAccess.class, ToolRunService.class, AgentHistoryClient.class,
    InternalAgentChannelStreamController.class, AgentChannelActivityRelay.class, JooqChannelBindingSnapshotRepository.class,
    InternalRuntimeAuth.class,
    AuthService.class, PlatformAuthorization.class, ApiExceptionHandler.class})
class TaskE2eServer {
    static final java.util.List<String> HTTP_FAILURES = new java.util.concurrent.CopyOnWriteArrayList<>();
    @Bean org.springframework.web.filter.OncePerRequestFilter internalFailureDiagnostics() {
        return new org.springframework.web.filter.OncePerRequestFilter() {
            protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,jakarta.servlet.FilterChain chain) throws java.io.IOException,jakarta.servlet.ServletException {
                var capture=new org.springframework.web.util.ContentCachingResponseWrapper(response);
                try {
                    chain.doFilter(request,capture);
                    if(capture.getStatus()>=400&&request.getRequestURI().startsWith("/api/internal/"))
                        HTTP_FAILURES.add(request.getRequestURI()+" HTTP "+capture.getStatus()+" "+new String(capture.getContentAsByteArray(),java.nio.charset.StandardCharsets.UTF_8));
                } finally {capture.copyBodyToResponse();}
            }
        };
    }
    @Bean CatalogRepository catalog(DSLContext db, ObjectMapper mapper) { return new JooqCatalogRepository(db, mapper); }
    @Bean PlatformTransactionManager transactions(DataSource source) { return new DataSourceTransactionManager(source); }
    @Bean AgentStore agentStore(DSLContext db,ObjectMapper mapper) { return new AgentStore(db,new JooqJsonbSupport(mapper)); }
    @Bean ChannelOutboundFramePublisher channelPublisher() { return org.mockito.Mockito.mock(ChannelOutboundFramePublisher.class); }
    @Bean CatalogService catalogService(CatalogRepository repository,@Value("${agentyard.agent-runtime.base-url}") String runtime,
        @Value("${agentyard.internal-auth.token}") String token) {
        var actual=new ExtensionRegistrationService(new ExtensionRegistrationProperties(null),"http://127.0.0.1:1",runtime);
        // This web-only harness has no channel process. Its connector registration
        // still comes from the production loader and its manifest from actual Python HTTP.
        var registration=org.mockito.Mockito.mock(ExtensionRegistrationService.class);
        org.mockito.Mockito.when(registration.services()).thenReturn(actual.services().stream()
            .filter(r->r.registrationId().equals("core-agent-runtime")).toList());
        var client=java.net.http.HttpClient.newHttpClient();
        var definitions=new ExtensionDefinitionService(registration,(url,headers)->{
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET();headers.forEach(request::header);
            var response=client.send(request.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new java.io.IOException("runtime manifest unavailable");return response.body();
        },token);
        var knowledge=new KnowledgeService(new InMemoryKnowledgeRepository(),repository,
            new KnowledgeServiceClient("http://127.0.0.1:8091",token),new KnowledgeWorkflowGateway() {
                public void startImport(String base,String job) { throw new UnsupportedOperationException("knowledge import not part of this fixture"); }
                public void startIndexBuild(String base,String snapshot) { throw new UnsupportedOperationException("knowledge indexing not part of this fixture"); }
            });
        return new CatalogService(repository,knowledge,PlatformEventService.disabled(),null,definitions,null,null);
    }

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
