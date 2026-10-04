# Spec：管理联调与全库文字召回

1. 现有管理、解析任务、原件、摘要、整理和召回HTTP字段及路由保持；Controller负责协议、Service负责业务，Repository与Model合同无新增层。
2. 独立召回页默认全库时，完整授权EvidenceScope仍包含全部当前publication，文字检索候选仅取其中具有真实文字/OCR publication entry的资料。纯视觉描述不变成文字证据；所有远程阶段和返回前继续复验完整scope、ACL、target及配置。
3. 显式document_ids仍逐一验证；包含无文字材料的所选资料整次拒绝，不能丢弃、改成全库或部分成功。显式空数组保持empty_scope。
4. 全库非空但无文字publication时返回原有empty/no_matches、完整scope_count、空matches，不发送嵌入/检索/重排请求；真正空库返回empty_scope。协议形状、排序、分块定位和SHA保持。
5. 新增显式本机工作台启动入口，复用run-dev.sh的不变JAR，独立数据目录、loopback和development_headers；开启managed设置与文本摄取/索引/答案流程，模型未应用时真实报告未配置。默认不灌演示行、不接旧数据、不保存密钥、不自动构建或调用模型。
6. 依现行负责人停测要求，不启动自动化测试、语法/格式检查或审计。允许跳过全部测试构建、实际浏览器操作合成TXT，记录管理和任务结果；本轮没有新模型调用额度，真实召回与生成不执行，不据代码或构建声称质量通过。
