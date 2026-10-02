# Verification：图片OCR词级区域

状态：IMPLEMENTATION；2026-09-09。本记录只认证0010，不认证纯视觉、音视频、网页或生产。

## 先失败证据

| 验证 | 实际结果 |
| --- | --- |
| ImageMainlineHttpTest.imageTravelsThroughOcrIndexAnswerAndOriginalReadback | 1项失败：原上传→OCR→索引→问答→来源正常，但region_kind预期ocr_word实际缺失；不是环境失败 |
| ImageTsvParserTest.preservesUnicodeRepeatedWordsAndLowConfidenceTailAcrossLines | 1项安全Failure，TSV解析stub尚未实现 |
| ImageRegionMigrationTest.newAuthorityUsesVersionSixWithAnImageRegionSidecar | 1项失败：期望v6，实际v5 |
| ImageRegionServiceTest.completedImageSurvivesRestartAndSourceReturnsOnlyIntersectingWord | 1项失败：图片提交stub返回false |
| ImageTsvParserTest.rejectsInitialWordBomRatherThanReturningShiftedCodePointRegions | 5项中4通过/1失败：首词BOM被TextParser移除，Tool未拒绝不一致结果；原Service最终覆盖校验仍在，不是已接受的错误引用 |

以上由root在独立临时构建目录串行执行，实际JDK21；未移动/隔离源码，未覆盖运行中jar。HTTP/SQLite/任务是真实实现，OCR及模型/向量依赖明确为受控替身；原生OCR另验。

## 最终验收

第一轮组合13项全通过（09:44:22），含真实本机Tesseract5.5.3/eng的ImageOcrMainlineIT完整HTTP，确认金额650的词框不等于整图且位于已绘制文字范围，引用source SHA与原图字节一致。模型/Milvus仍为本机协议替身。最终Tool字面保真修复后整个ImageTsvParserTest5项、Process1项、Options1项全通过（09:47:37）。

| 验证 | 冻结后实际结果 |
| --- | --- |
| 区域/迁移相关回归 | 78项通过，含新增15项v6迁移/不可变与9项摄取/重启/区域交集，旧四份迁移测试保留真实历史格式断言；09:49:58 |
| 完整Java clean verify | 900项，0失败/错误/跳过；实际Temurin21.0.12.1+1、Maven3.9.9；09:52:36 |
| 原测试保留 | 上轮871项testcase身份逐项比对，缺失0；不以测试总数抵消丢失用例 |
| Spotless及分层 | 264个Java文件格式通过，完整回归含原架构测试；无删除、跳过或放宽失败门禁 |
| JaCoCo | LINE 6635/7092 = 93.5561%；BRANCH 3361/4055 = 82.8853%，原双80%通过 |
| Node | 73项通过，0失败/取消/跳过；前端源码未变 |
| 最终真实OCR HTTP | ImageOcrMainlineIT 1项通过，09:53:29；Tesseract5.5.3/eng，合成英文PNG，固定金额650 USD与原图/词框一致；模型和Milvus仍为协议替身 |
| 独立审查 | 非实现者只读Standards scoped PASS / Spec scoped PASS，见[REVIEW](REVIEW.md) |
| 源码绑定 | 288个Java/资源/构建与Node验证输入与实际临时构建逐字节一致，差异0；[source-manifest](source-manifest.json)包含输入、产物/日志摘要、旧新case清单摘要 |
| 敏感内容与工件检查 | index/worktree扫描空结果；43个未追踪文件用同一inspect逻辑扫描无发现；306条相对Markdown链接均存在；git diff --check通过，最终已验证输入未变化。不是聊天历史或全机密钥清除证明 |

验收命令（项目根目录；实际执行使用上述JDK、离线Maven与独立依赖缓存）：

```sh
mvn -s .mvn/settings.xml -gs .mvn/settings.xml spotless:apply
mvn -s .mvn/settings.xml -gs .mvn/settings.xml clean verify
node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs
RAG_IMAGE_OCR_IT_EXECUTABLE=/absolute/path/to/tesseract \
RAG_IMAGE_OCR_IT_REVISION=tesseract-5.5.3-eng \
mvn -s .mvn/settings.xml -gs .mvn/settings.xml -Dtest=ImageOcrMainlineIT test
```

运行日志、JUnit XML和构建JAR保留在本机临时构建中，不上传Git；portable manifest以SHA绑定本次实际输入和输出，不等于第三方发布签名。

## 边界与计划偏离

按计划完成词级区域纵切，未增加通用抽象或新权限体系。唯一补充是已复现的首词BOM字面保真守卫，不改变共享TextParser算法。旧migration fixture为v6适配：当前版本预期推进，历史v5损坏/不一致标记仍还原真实v5再执行原断言，不混淆历史场景。

实际原生OCR只验证一张合成英文PNG。JPEG准入/原图回读、Unicode/多行和重复词有组件测试，不等于真实中文、多版面或全部格式识别质量；词框不是字符框，坐标合法不证明OCR语义永远正确。网页高亮/预览、真实图片provider/Milvus质量、视觉/扫描PDF、音频/视频、摘要与生产目标仍未完成。v6备份回滚仅保留备份时刻数据，不自动迁移或覆盖旧服务。

本轮云模型调用0次，不挪用历史文本额度；未推送、未部署、未改前端/旧服务/旧数据。
