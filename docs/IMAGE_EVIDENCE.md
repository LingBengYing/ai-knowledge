# 图片文字证据：本机开发入口

当前变更[0010词级区域](changes/0010-image-regions/intent.md)，基于[0009图片文字](changes/0009-image-evidence/intent.md)。PNG/JPEG中的机器识别文字复用现有文本索引与问答，原图经受保护的引用接口回读；0010为引用增加OCR词级位置。它不是字符级框、完整图片视觉理解、扫描PDF、音频/视频或生产能力。[0010验收](changes/0010-image-regions/verification.md)记录真实Tesseract合成英文PNG及最终900 Java/73 Node；0009报告仅认证历史源码。

## 启用

先安装Tesseract及需要的语言数据，验证 `tesseract --version` / `tesseract --list-langs`。运行环境、二进制和语言数据版本由部署者明确固定；配置不是自动下载/安装器。Java不调用Python业务服务，也不把图片发给云端OCR。

在全新Java数据目录、字面loopback development/test环境中，保持既有[文本模型与Milvus配置](TEXT_ADAPTERS.md)，新增环境变量：

```sh
RAG_INGESTION_ENABLED=true
RAG_IMAGE_OCR_ENABLED=true
RAG_IMAGE_OCR_EXECUTABLE=/absolute/path/to/tesseract
RAG_IMAGE_OCR_LANGUAGE=eng
RAG_IMAGE_OCR_REVISION=your-pinned-engine-and-language-data-revision
RAG_INDEXING_ENABLED=true
RAG_ANSWERS_ENABLED=true
```

值须真正注入进程环境；应用不会自动source `.env`。中文需要安装匹配的语言数据并配置 `chi_sim+eng`，不能由英文样本通过推定中文准确率。不开启图片选项时原PDF/TXT/MD行为保持；所有生产guard继续有效。

## 正常HTTP流程

1. `POST /v1/documents?filename=budget.png`，Content-Type为application/octet-stream，body为原图。最大10MiB/1200万像素，返回202任务。
2. `GET /v1/ingestions/{task_id}` 等待parsed。图片转录保存为page 1，独立parser_revision包含OCR运行版本和语言；parsed还没有索引。
3. `POST /v1/documents/{document_id}/index`，跟踪indexings任务至indexed。
4. `POST /v1/answers`，提供question和document_ids，沿用范围前置、权威hydrate、重排/摘录和事实校验。
5. 回读答案的source_url。图片来源除了原citation，另有image：type、mime_type、width、height、bbox、coordinate_system、text_origin和content_url。
6. 用同一受信身份请求content_url，返回与source_sha256一致的原PNG/JPEG字节。来源属于原回答者且完整选中文档当前仍授权/active；撤下后不可继续回读。不是任意文件ID下载或公开直链。

`text_origin=machine_ocr`，page/start/end是转录文字中的Unicode code point，不是图上字符坐标。`bbox=[0,0,1,1]`仍表示整图。新图片通过一次Tesseract TSV同时生成文字和词框，`region_kind=ocr_word`与`regions`只给出引用相交词的转录区间及归一化xyxy框，详见[API](API.md)。模型不生成引用位置，source回读也不重新OCR。旧图片保留整图定位且不输出regions；音视频Range和播放器未实现，前端详情页未改，不能把API回读说成网页预览已验收。

0010的v6增量迁移在独立Java数据目录中先备份v5再新增不可变区域附表；旧页、分块、原图、publication和trace不重写。新上传parser身份为`java-image-ocr-v2-tsv:<运行版本>:<语言>`。旧程序不能直接打开v6；回滚需独立使用迁移前备份，新写资料不在该备份中。不在旧服务数据库上执行迁移。

## 可重放验证

默认 `mvn -s .mvn/settings.xml -gs .mvn/settings.xml clean verify` 包含ImageMainlineHttpTest，使用明确OCR进程替身与本机模型/Milvus协议替身；真实Spring/SQLite/解析与索引任务均不被替换。

显式原生OCR验收：

```sh
RAG_IMAGE_OCR_IT_EXECUTABLE=/absolute/path/to/tesseract \
RAG_IMAGE_OCR_IT_REVISION=your-pinned-engine-and-language-data-revision \
mvn -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dtest=ImageOcrMainlineIT test
```

该IT实际识别合成图片并贯通HTTP，仍使用本机模型/向量替身，不是远程模型质量或真实Milvus图片验收。缺显式可执行文件/版本直接失败，不skip或使用假OCR。无自动计费重试。

## 后续

图片词级区域之后继续视觉事实、扫描/图文PDF → 音频转写/时间引用 → 视频音轨/关键帧与逐事实联合证明。文件摘要从已编译证据生成，只用于导航，不作为事实答案唯一依据。
