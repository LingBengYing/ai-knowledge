# Spec：核对并继续使用已有图片、音频向量

状态：CONTRACT_FROZEN；必须先实际行为 RED，后实现、GREEN 及原门禁。

1. 使用原 `POST /v1/documents/{document_id}/reindex`、严格 `base_publication_id`、TaskResult 及取消/重试流程。当前编辑 ACL、观察到的 active publication、原件完整 SHA、完整解析材料、parser、文本 target 和无其他在途任务仍是前置条件。模型保存、逐角色连接测试、明确应用和完整范围召回测试保持原流程。
2. 无媒体 receipt 的资料保持 0035 行为。存在 receipt 时，必须枚举本次 base 的全部有效图片、音频 receipt，且每一份具有完整 authority 来源和准确 runtime target 的只读复验器。缺配置或目标不匹配安全拒绝，不能静默丢掉不匹配 receipt。历史原 receipt 行全部保留；保留历史不等于授予新 base 有效资格。
3. 新任务冻结完整 receipt 集合及 base 身份。新文本 generation 仍完整建立和远端验证。每份独立媒体 manifest 在 authority 事务外，以有界、可取消的生产路径实际完整复验，包含完整物理 ID、source/PCM SHA、float32 digest、数量、workspace/document/generation、schema/index 和投影身份；只检查 authority 的 manifest 字符串不算远端验证。不得 create、load、upsert 旧媒体 collection，也不得调用媒体 embedding、ASR 或 decoder。
4. 新 immutable publication→origin receipt 关联保存本次 base 及原 receipt provenance。独立 vector generation、physical IDs、entry digest 和 manifest 保持原值，新 text physical IDs 按新 text generation 与同一 evidence ID 全量派生、逐项核对。重复重建直接关联 origin，不构成无限递归链。不能复制旧 receipt 并声称是新媒体构建。
5. 完成时在同一短 authority 事务复验 claim/token/attempt/generation、creator 当前编辑 ACL、原件/解析/text target/base、完整冻结 receipt 集合和完整复验结果；新文本 entries、媒体关联、active CAS 和终态一起提交。不允许短暂发布缺媒体 receipt 的新 base。遗漏/多出 receipt、远端缺项、错误 digest、配置改变、撤权、取消、超时、重启中断或 late result 均保留旧 active，任务按原安全终态处理。
6. 图片、音频状态 GET 的 wire shape 不变。新 base 状态返回新 publication 身份和原 vector generation，服务器实际完成关联与完整源映射。dense candidate→新文本物理定位→保存原图/完整时间来源在重启后仍有效。完整 scope 不能过滤失败资料；旧回答来源按既有 active 校验失效，新查询及来源使用新 publication。
7. 新 `text_reindex_with_vectors` 表示实际已装配复验继承路径，仍需原 `text_index/indexings/text_reindex` 和服务器当前 `can_reindex=true`。能力不以媒体问答启用代替目标和材料资格；无新能力的旧服务前端行为保持。管理资格不执行远端请求；远端缺失由显式任务失败呈现。
8. UI 仍叫“重建文本索引”。确认说明文字嵌入与向量服务可能产生费用，若已有媒体向量则完整核对后继续使用，不重新生成媒体向量、解析、转录或换原件。旧版本在任务期间可用；只有成功后的授权回读取得新 publication 才清旧答案、来源与召回结果，重读新 base 媒体状态。保留问题、完整范围及未保存整理草稿；不自动重试、补建或调用媒体 POST。

验收：真实本机 SQL 迁移与 rollback/immutable 约束；图片、音频完整集合重复继承与重启；完整远端缺项拒绝/旧索引继续可用；实际 HTTP 能力、管理、任务、dense 召回和同版本来源链；真实 DOM 新能力正常操作及旧服务兼容；原测试身份、架构、LINE/BRANCH 双80%、默认完整回归、前端语法/完整回归和相关 native。各项按实际执行记录，不以草稿认证。
