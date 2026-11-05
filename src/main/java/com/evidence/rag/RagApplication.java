package com.evidence.rag;

import com.evidence.rag.config.AnswersSettings;
import com.evidence.rag.config.BatchProcessingSettings;
import com.evidence.rag.config.IndexingSettings;
import com.evidence.rag.config.IngestionSettings;
import com.evidence.rag.config.RagSettings;
import com.evidence.rag.worker.indexing.AudioVectorWorker;
import com.evidence.rag.worker.indexing.ImageVectorWorker;
import com.evidence.rag.worker.indexing.TextIndexWorker;
import com.evidence.rag.worker.parser.ParserWorker;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
  RagSettings.class,
  IngestionSettings.class,
  IndexingSettings.class,
  AnswersSettings.class,
  BatchProcessingSettings.class
})
public class RagApplication {
  public static void main(String[] args) {
    if (args.length == 1 && "--audio-vector-worker".equals(args[0])) {
      AudioVectorWorker.main(new String[0]);
      return;
    }
    if (args.length == 1 && "--image-vector-worker".equals(args[0])) {
      ImageVectorWorker.main(new String[0]);
      return;
    }
    if (args.length == 1 && "--index-worker".equals(args[0])) {
      TextIndexWorker.main(new String[0]);
      return;
    }
    if (args.length > 0 && "--parse-worker".equals(args[0])) {
      ParserWorker.main(java.util.Arrays.copyOfRange(args, 1, args.length));
      return;
    }
    SpringApplication.run(RagApplication.class, args);
  }
}
