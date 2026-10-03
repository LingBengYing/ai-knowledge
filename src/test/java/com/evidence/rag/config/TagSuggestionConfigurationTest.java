package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.controller.TagSuggestionController;
import com.evidence.rag.service.TagSuggestionService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class TagSuggestionConfigurationTest {
  @Test
  void disabledSynopsisDoesNotRegisterTagSuggestionsOrRequireAnyProvider() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(new MockEnvironment());
      context.register(SynopsisConfiguration.class, TagSuggestionController.class);
      context.refresh();
      assertTrue(context.getBeansOfType(TagSuggestionService.class).isEmpty());
      assertTrue(context.getBeansOfType(TagSuggestionController.class).isEmpty());
    }
  }
}
