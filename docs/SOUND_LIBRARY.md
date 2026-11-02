# 独立声音知识库

0028提供无语音声音资料的原文件与显式索引。前端0017的「声音理解」保存原音频后打开资料详情；编辑者建立声音索引。

> 2026-11-02已删除声音问答与来源接口：`POST /v1/sound-answers`、`POST /v1/sound-query-answers`、`GET /v1/sound-sources/{answer_id}/{ordinal}`及`/content`不再路由（404），对应Service、请求映射与trace读写代码一并删除。原文件上传、索引、撤下清理保留；`sound_traces`等trace表结构保持不变但不再写入或读取。下文问答/来源段落仅为历史说明，后续新功能另行规划。原「音频转录」继续使用转录证据。两种索引可以绑定同一份真实原音频，声音索引不要求先建立speech publication。

## 接口与顺序

| 操作 | HTTP接口 | 结果 |
| --- | --- | --- |
| 保存声音 | `POST /v1/sound-documents` | 原文件字节；一次UTF-8百分号编码的`X-Filename`；四字段原文件receipt，无摄取任务 |
| 读取索引 | `GET /v1/documents/{id}/sound-index` | 十二字段missing/available状态，reader可读，不解码或调用模型 |
| 建立索引 | `POST /v1/documents/{id}/sound-index` | 当前editor显式建立全部连续PCM窗口；完整同profile索引幂等回读 |

索引接口禁止query/body；上传上限20MiB。文件名百分号只解码一次，字面`+`保留。完整响应字段见[spec](changes/0028-sound-library/spec.md)与[固定接口](changes/0028-sound-library/interfaces.md)。

## 原文件与事实身份

原上传只写入独立声音原文件authority及资料ACL，不调用ASR、解码或模型。显式构建使用实际16kHz mono S16LE样本，连续覆盖静音与尾部，每窗口最多30秒，原文件最多600秒。新构建attempt使用独立generation，只有完整投影receipt和当前source/profile/ACL复验通过才封存；v19沿既有备份迁移，仅追加六个声音表及immutable guards。

（历史，已删除）声音描述只用于召回。模型draft和独立verify分别收到完整问题及实际库内窗口WAV，单个窗口必须证明完整问题；不同合格事实集合保守拒答。来源标明模型判断，不生成转录quote。时间来自服务器样本边界，精度为`server_window`，不承诺事件逐词时间。事实JSON SHA、question/model/policy、原文件/PCM SHA及publication身份共同绑定proof。

（历史，已删除）来源回读复验原回答者、整个已保存范围的当前权限、publication/profile和原字节SHA；重启后不调用模型或重新解码。撤下先写tombstone，再擦除独立blob，历史metadata/proof保持不可变。

## 配置和验证边界

`RAG_SOUND_ENABLED`默认false，当前只允许development/test字面loopback并要求ingestion装配。原文件decoder、Google Interactions模型、Google多模态embedding及独立`java_` Milvus collection必须显式配置，模型版本、decoder版本、维度和profile必须一致。配置键以[application.properties](../src/main/resources/application.properties)的`rag.sound.*`为准；凭据只由私有运行环境提供。

默认处理预算120秒、最多2项在途、同资料一次构建，无自动建立或重试。实际SoundLibraryService装配时，config仍声明`sound_upload/sound_index/sound_answers/sound_sources/sound_query_attachments`五项能力；后三项自2026-11-02起只是网页兼容标记（网页`soundEnabled`仍检查这五项），背后已无接口，待网页改为只检查上传/索引后删除。启动不访问provider。

本机实际运行记录见[0028验证](changes/0028-sound-library/verification.md)。合成Spring/SQLite/FFmpeg和loopback协议替身不认证真实声音模型、Milvus语义质量、ASR、网页或生产。用户负责页面验收，Git和部署归原责任方；本切没有真实收费模型调用，完整开发目标保持active。
