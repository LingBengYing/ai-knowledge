package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AgentConfiguration(
    boolean enabled, String engine, @JsonProperty("max_steps") int maxSteps) {}
