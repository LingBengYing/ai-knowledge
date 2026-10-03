package com.evidence.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit opt-in for application-managed cleanup, independently of withdrawal. */
@ConfigurationProperties("rag.document-cleanup")
public record DocumentCleanupSettings(boolean enabled) {}
