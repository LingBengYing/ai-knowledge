package com.evidence.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit local withdrawal requests; does not enable physical deletion or other text features. */
@ConfigurationProperties("rag.document-removal")
public record DocumentRemovalSettings(boolean enabled) {}
