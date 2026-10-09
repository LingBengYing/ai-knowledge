# 0057 发布后知识数据重置

2026-10-09，负责人明确要求“知识数据清理一下，我重新弄知识上去”。15:30:17 +08完成服务器验证：当前知识内容已清空，保留原数据私有备份及现有模型/检索/运行配置。本操作是用户新增授权，覆盖此前“旧资料保持不变”的范围边界；前面的发布时数据一致性证据仍是当时事实。

## 实际结果

- 原10份资料及解析、索引、引用/答案历史、目录和Wiki相关内容已从当前库清空。真实生产Store创建的新schema32库中，90张知识/任务/历史表全部为0行；使用新的library技术身份，组织仍为org-main。
- 只删除生产Milvus数据库knowledge、集合java_knowledge_20261002中`workspace_id=org-main`且document_id属于本次10份资料的13条向量记录。先完整导出6个必要字段和完整dense向量，校验行数/身份/维度/有限数值，再执行定向删除；Strong读取确认剩余0条。没有删除或重建集合，另两个验证集合不动。
- `private-model-settings/`整个目录（包括sealed版本和锁文件）及`retrieval-settings.json`原字节保持。旧私有主文件的active_version为5，实际SQLite选择的是sealed v6；通过生产Store事务恢复selection整行与两个摘要，没有错误回退到5。
- 鉴权/组织/模型连接环境、密钥文件、systemd unit、免登录、Agent启用均保持。服务均active；资料、目录、知识页、草稿、待审阅接口返回total=0。没有发起模型、解析、索引或编译调用。

## 备份与恢复边界

私有备份：`/srv/ai-knowledge/releases/20261009-dbgpt-agent-0057/private/knowledge-reset-20261009T152728/`。保留停机一致`data/`、目录切换时封存的`original-data/`、精确向量导出`vectors.json`和完成/失败过程记录，父目录0700。内容未下载至公开文档或提交Git。

旧公开清理API未启用，且只覆盖受控资料清理、不覆盖全部Wiki/任务历史。本次未强开这些开发模式接口，也未删除schema trigger。使用独立的`KnowledgeResetProbe`调用实际SqliteAuthorityStore，要求旧重建任务表为空、selection.batch_id=null；对新表UPDATE既有id=1，保留所有原保护规则，90表空及再次打开已验。

保留备份、Milvus逻辑删除及空权威库满足重新上传的工作流，不声称存储介质或Milvus历史文件已经不可恢复擦除。公开入口重新开放之后没有回写旧库或旧向量，避免覆盖用户新上传。

## 权限检查发现与修复

初次目录切换完成、服务恢复后，实际Agent UID隔离检查捕获新目录0755/SQLite0644。这来自运维Java进程继承SSH默认umask；此前业务与模型一致性检查已通过，清理并未失败回滚。随后将新DATA目录及子目录设0700、文件0600，用实际Java/入口/Agent UID重新验证可运行及Agent不可直接读取数据库/密钥，通过后记录RESET_COMPLETED。

执行脚本已补确定性保护：在所有新建操作前设置umask0077，并在目录切换前显式收紧全部目录/数据文件模式。静态顺序检查通过；该补丁没有重新清理当前空库。初版脚本SHA256 `66e269f56dd4768b5ee6c7b0697024a35c415f36868cbb10605e860e1a93d1ec`，修正版`7fbe2793595f126eaca4fff154cc2101b82567334ccefcc6b2dd4c98cb627fea`。Probe class SHA256 `197830821dfd1483679ebc9917ddff63de6b95632a020b7ca9179f99352e2dc2`。

原始摘要在本机`.local/release-0057-20261009/KNOWLEDGE_RESET.json`、`knowledge-reset-public.json`及服务器release下`KNOWLEDGE_RESET.json`。没有用截图或真实模型效果替代这些实际计数、摘要和权限检查。
