package com.evidence.rag.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Existing media modules require their actual, complete legacy text composition. */
public final class LegacyTextCondition implements Condition {
  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return context.getEnvironment() instanceof ConfigurableEnvironment environment
        && ManagedTextSettings.legacyAvailable(environment);
  }
}
