package com.evidence.rag;

import com.evidence.rag.config.AnswersSettings;
import com.evidence.rag.config.IndexingSettings;
import com.evidence.rag.config.IngestionSettings;
import com.evidence.rag.config.PersistenceConfiguration;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.worker.indexing.AudioVectorWorker;
import com.evidence.rag.worker.indexing.ImageVectorWorker;
import com.evidence.rag.worker.indexing.IndexWorker;
import com.evidence.rag.worker.indexing.SoundIndexWorker;
import com.evidence.rag.worker.indexing.VideoAvIndexWorker;
import com.evidence.rag.worker.parser.ParserWorker;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
  RagProperties.class,
  IngestionSettings.class,
  IndexingSettings.class,
  AnswersSettings.class
})
public class RagApplication {
  public static void main(String[] args) {
    if (args.length == 1 && "--video-av-index-worker".equals(args[0])) {
      VideoAvIndexWorker.main(new String[0]);
      return;
    }
    if (args.length == 1 && "--sound-index-worker".equals(args[0])) {
      SoundIndexWorker.main(new String[0]);
      return;
    }
    if (args.length == 1 && "--audio-vector-worker".equals(args[0])) {
      AudioVectorWorker.main(new String[0]);
      return;
    }
    if (args.length == 1 && "--image-vector-worker".equals(args[0])) {
      ImageVectorWorker.main(new String[0]);
      return;
    }
    if (args.length == 1 && "--index-worker".equals(args[0])) {
      IndexWorker.main(new String[0]);
      return;
    }
    if (args.length > 0 && "--parse-worker".equals(args[0])) {
      ParserWorker.main(java.util.Arrays.copyOfRange(args, 1, args.length));
      return;
    }
    if (args.length > 0 && "--seed-demo".equals(args[0])) {
      if (args.length != 2) {
        throw new IllegalArgumentException("Usage: --seed-demo NEW_DATA_DIRECTORY");
      }
      PersistenceConfiguration.seedDemo(Path.of(args[1]));
      System.out.println("已创建合成资料元数据；未上传、解析或索引任何原文件。");
      return;
    }
    SpringApplication.run(RagApplication.class, args);
  }
}
