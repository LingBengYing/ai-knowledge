package com.evidence.rag.config;

import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.repository.SqliteAuthorityStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every runtime root shares the authority owner's operation gate, including when cleanup is off.
 */
@Configuration(proxyBeanMethods = false)
public class LibraryOperationConfiguration {
  @Bean
  LibraryOperationGate libraryOperationGate(SqliteAuthorityStore store) {
    return store.operationGate();
  }
}
