package com.evidence.rag.config;

import com.evidence.rag.model.domain.AuthenticationOptions;
import com.evidence.rag.security.authentication.JwtAuthenticator;
import com.evidence.rag.security.web.ExternalEntryPolicy;
import com.evidence.rag.security.web.RequestAuthenticator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
  @Bean
  ExternalEntryPolicy externalEntryPolicy(Environment environment, RagProperties properties) {
    return new ExternalEntryPolicy(
        environment.getProperty("rag.external-entry.enabled", Boolean.class, false),
        environment.getProperty("rag.external-entry.public-origin", ""),
        environment.getProperty("server.address", "127.0.0.1"),
        properties.authMode());
  }

  @Bean
  JwtAuthenticator jwtAuthenticator(RagProperties p) {
    return new JwtAuthenticator(
        new AuthenticationOptions(
            p.authMode(), p.workspaceId(), p.jwtSecret(), p.jwtIssuer(), p.jwtAudience()),
        Clock.systemUTC());
  }

  @Bean
  RequestAuthenticator requestAuthenticator(RagProperties p, JwtAuthenticator jwt) {
    return new RequestAuthenticator(p.authMode(), p.workspaceId(), jwt);
  }
}
