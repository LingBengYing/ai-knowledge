package com.evidence.rag.controller;

import com.evidence.rag.security.web.ExternalEntryPolicy;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal startup handshake; the external gateway does not publish this route. */
@RestController
public final class ExternalEntryController {
  private final ExternalEntryPolicy policy;

  public ExternalEntryController(ExternalEntryPolicy policy) {
    this.policy = policy;
  }

  @GetMapping("/health/entry-policy")
  public ResponseEntity<Map<String, Object>> policy() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            Map.of(
                "enabled",
                policy.enabled(),
                "public_origin",
                policy.publicOrigin(),
                "auth_mode",
                "jwt",
                "stage",
                "external_preview"));
  }
}
