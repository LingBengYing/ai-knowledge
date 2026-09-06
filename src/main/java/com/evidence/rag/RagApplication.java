package com.evidence.rag;

import com.evidence.rag.config.DemoFixtures;
import com.evidence.rag.config.RagProperties;
import com.evidence.rag.config.RuntimeGuard;
import com.evidence.rag.corpus.ParserWorker;
import com.evidence.rag.ingestion.IngestionSettings;
import com.evidence.rag.management.ManagementModule;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
@EnableConfigurationProperties({RagProperties.class, IngestionSettings.class})
public class RagApplication {
  public static void main(String[] args) {
    if (args.length == 1 && "--parse-worker".equals(args[0])) {
      ParserWorker.main(new String[0]);
      return;
    }
    if (args.length > 0 && "--seed-demo".equals(args[0])) {
      if (args.length != 2)
        throw new IllegalArgumentException("Usage: --seed-demo NEW_DATA_DIRECTORY");
      DemoFixtures.seed(Path.of(args[1]));
      System.out.println("已创建合成资料元数据；未上传、解析或索引任何原文件。");
      return;
    }
    SpringApplication.run(RagApplication.class, args);
  }

  @Bean(destroyMethod = "close")
  ManagementModule managementModule(RagProperties properties, Environment environment) {
    RuntimeGuard.check(properties, environment.getProperty("server.address"));
    return new ManagementModule(properties.dataDirectory());
  }
}
