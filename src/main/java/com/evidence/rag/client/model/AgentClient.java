package com.evidence.rag.client.model;

import com.evidence.rag.model.dto.AgentProtocol;

/** Real DB-GPT HTTP Adapter and local contract-test Adapter share this boundary. */
@FunctionalInterface
public interface AgentClient {
  AgentProtocol.Proposal execute(AgentProtocol.RunRequest request);
}
