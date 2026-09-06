package com.evidence.rag.security;

import com.evidence.rag.config.RagProperties;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AuthenticationConfiguration {
  @Bean
  AuthenticationModule authenticationModule(RagProperties properties) {
    return new AuthenticationModule(properties, Clock.systemUTC());
  }
}
