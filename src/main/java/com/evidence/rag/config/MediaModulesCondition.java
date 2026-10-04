package com.evidence.rag.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Real media resources may serve either configured legacy text or a managed text bundle. */
public final class MediaModulesCondition implements Condition {
  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return context.getEnvironment()
            .getProperty("rag.model-configuration.enabled", Boolean.class, false)
        || context.getEnvironment() instanceof ConfigurableEnvironment environment
            && ManagedTextSettings.legacyAvailable(environment);
  }
}
