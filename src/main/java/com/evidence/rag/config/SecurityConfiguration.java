package com.evidence.rag.config;

import com.evidence.rag.model.domain.AuthenticationOptions;
import com.evidence.rag.security.authentication.JwtAuthenticator;
import com.evidence.rag.security.web.RequestAuthenticator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
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
