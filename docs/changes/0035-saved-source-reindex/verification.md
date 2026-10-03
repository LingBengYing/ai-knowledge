# Verification：保存材料重建文本索引

状态：LOCAL_VERIFIED，完整目标ACTIVE；未部署，页面由用户验收。

保存完整解析材料的资料现在可明确创建新索引任务。处理期间沿用原发布版本，成功后同一事务切换；失败、取消或重启中断保留旧索引。管理列表真实can_reindex、实际GET /v1/config能力、严格POST、详情确认、独立新任务、终态授权回读及同parsed revision的新publication均已在本机正常链验证。成功后旧回答来源遵循原current策略失效，页面清除旧结果并要求重新查询，保留问题、范围与整理草稿。

| 实际检查 | 结果 |
| --- | --- |
| 原POM clean verify | 3130项、412 suites，0失败/错误/跳过 |
| 原3075用例身份及多重性 | 完整保留 |
| LINE / BRANCH原双80%门禁 | 92.7733% / 80.4258%，通过 |
| 原ArchitectureRules及格式 | 通过；1027 Java文件 |
| 前端全量及check | 450项全部通过；原435及中间448身份多重性保留 |
| 六条Native媒体流程 | 第二轮各1通过，0失败/错误/跳过 |
| 最终生产class / JAR资源 | 762完整class、7资源逐字节一致 |
| 后端Node73 | 18输入精确相同，明确复用0032实跑证据，未重跑 |

执行证据位于工作区`.tools/reindex-preparation`：backend-clean-verify-first-result.json、backend-native-second-result.json、backend-release-audit-first.json、frontend-full-second-result.json、frontend-final-retention-first.json。JAR SHA256为d10c63430a297e278cd232835136140235df3dde570950662fb172c2fc3daf87。每次后台1043、前端64输入均执行前后相同；default完整验证之后仅两项不在默认suite的NativeIT oracle更新，当前生产与全部默认用例输入逐字节保持。最终Native实跑包含完整testCompile及Spotless检查；记录两份输入仅两文件差异，未谎称跨run全部输入相同。

原产品RED分别保留：HTTP/格式3项FAIL（route404对202、schema23对24）；前端13项中12FAIL/1PASS。同源GREEN后强化实际cap/management入口断言，该强化不冒充原产品RED。服务19项实测4FAIL，证明claim后独立向量receipt可让任务永远processing；完整worker身份和同事务失败终态修复后，同源19加原10项全部通过。新模型/索引调用仍在事务外，旧claim、ACL、generation、attempt、完整items和迟到隔离保持。

独立页面审查发现图片/音频向量建立后的重建按钮陈旧。新增两条实际DOM在原产品均FAIL，修复后同测试字节均PASS；只依据完整验证且匹配当前资料/发布版本的available状态暂时收紧入口，旧确认也被阻止。没有新增请求或改写server can_reindex，没有重建整理表单。原448 DOM源码是新增两条测试的完整字节前缀。

迁移测试26份必要适配有完整diff：57处current version23→24、真实逆24→23夹具调用、当前verifier及独立备份计数/旧前缀选择。原DDL历史方法、旧行为与拒绝断言保留。新v23→24非空历史/受控备份、完整CAS及清理全历史检查通过。相关首次201项中200通过，唯一ERROR为root新管理测试搜索q误传null；修正为已有合同要求的空字符串后该1项通过，原错误留存、不计业务RED。

Native首轮6项4PASS/2FAIL，两个旧整管理行比较唯一差异为预期can_reindex true→false。分别先断言旧true，再deepCopy完整旧JSON仅将该字段改false，继续完整JSON相等比较，原publication/source/jobs及全部其余字段仍相同。不是删除或忽略字段，不改产品让资格撒谎；原失败及精确差异留存，最终六项实跑通过。

独立审查报告与新的交接包以`.tools/reindex-handoff/SOURCE-MANIFEST.json`及VALIDATION为准，冻结及最终包审查需依据实际工件，不从本说明推定。旧0034/0023交接、失败与未选候选均只读保留。

仅本机合成资料、真实Spring/SQLite/DOM/FFmpeg/Tesseract及loopback协议服务；新增真实provider调用0，浏览器/部署/真实ASR和语义质量NOT_RUN。首切支持同target、完整保存parsed corpus且没有旧base图片/音频receipt；standalone sound/video-av、receipt迁移、真正embedding/projection迁移及原文件替换仍是后续范围。usage/计费取消，不新增实验；Git、旧业务数据、部署由原责任方处理。
