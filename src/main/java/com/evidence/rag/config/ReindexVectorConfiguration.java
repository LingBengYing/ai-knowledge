package com.evidence.rag.config;

import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.service.ReindexVectorVerifier;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Existing explicit projection settings only; constructing this graph sends no remote requests. */
@Configuration(proxyBeanMethods = false)
public class ReindexVectorConfiguration {
  @Bean
  ReindexVectorVerifier reindexVectorVerifier(
      ObjectProvider<ImageEmbeddingSettings> images,
      ObjectProvider<AudioEmbeddingSettings> audios) {
    var targets = new LinkedHashMap<String, IndexTarget>();
    var projections = new LinkedHashMap<String, MilvusRestProjection.Settings>();
    var image = images.getIfAvailable();
    if (image != null) {
      targets.put("image", image.target());
      projections.put("image", image.projection());
    }
    var audio = audios.getIfAvailable();
    if (audio != null) {
      targets.put("audio", audio.target());
      projections.put("audio", audio.projection());
    }
    return new ReindexVectorVerifier(targets, projections);
  }
}
