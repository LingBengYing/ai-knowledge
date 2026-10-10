package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModelValidationBudgetTest {
  @Test
  void concurrentProvidersShareOneBudgetAndFailuresNeverRefundIt() throws Exception {
    var budget = new ModelHttpTransport.ValidationBudget(20);
    var dispatched = new AtomicInteger();
    var rejected = new AtomicInteger();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 100; i++) {
        executor.submit(
            () -> {
              try {
                budget.reserve();
                dispatched.incrementAndGet();
              } catch (TextModels.Failure failure) {
                assertEquals("model_request_budget_exhausted", failure.code());
                rejected.incrementAndGet();
              }
            });
      }
    }
    assertEquals(20, dispatched.get());
    assertEquals(80, rejected.get());
    assertThrows(TextModels.Failure.class, budget::reserve);
  }

  @Test
  void ordinaryRuntimeHasNoRequestCountLimit() {
    var budget = new ModelHttpTransport.ValidationBudget(0);
    for (int i = 0; i < 1000; i++) budget.reserve();
    assertThrows(IllegalArgumentException.class, () -> new ModelHttpTransport.ValidationBudget(-1));
  }
}
