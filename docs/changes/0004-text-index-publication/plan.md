# Plan

1. 冻结下列Interface和验收，再先失败测试；不并发运行共享target的Maven。
2. authority worker拥有ManagementModule的v3 migration/index任务/publication及专属测试。projection worker拥有RetrievalProjection/MilvusRestProjection全量验证及专属测试。process worker拥有indexing进程协议/模型与投影编排及专属测试。主线程拥有配置、HTTP、启动接线、前端能力/状态、文档、集成和最终验证。共享工作树保留他人改动。
3. authority Interface：IndexTarget(embeddingIdentity,projectionIdentity,modelRevision,dimensions)；IndexSegment(segmentId,ordinal,page,start,end,text,textSha256)；IndexClaim(jobId,documentId,revisionId,workspaceId,attempt,token,sourceSha256,parserRevision,target,segments,projectionGenerationId)。createIndexing(actor,doc,target)、claimIndexing(workspace)、isIndexingClaimCurrent、completeIndexing(claim,entryDigests,verified)、failIndexing、indexingStatus、cancelIndexing、retryIndexing(actor,job,target)。所有records防御复制、redacted toString；每次领取写入不可变attempt/generation台账。
4. projection Interface：identity()；physicalSegmentId(generationId,sourceSegmentId)；RevisionManifest(workspaceId,documentId,revisionId,entryDigests)、VerifiedRevision(projectionIdentity,manifestSha256,segmentCount)、verify(RevisionManifest)。远端revisionId是projection generation，entryDigests按physical ID索引；HTTP source revision不变。提供同一确定性physical ID、Entry摘要和manifest摘要实现，authority/worker不各自发明编码。MAX_BATCH原限制保留，索引实际batch≤16；不能保留全部向量在内存。
5. 独立process Interface：ProcessTextIndexer(settings,timeout).index(IndexClaim) → IndexingResult(entryDigests,verified)，close取消；IndexWorker仅通过显式二进制协议收到配置和快照。main入口先于Spring启动处理--index-worker，不能打开authority DB。生产Adapter和真实可控进程/HTTP替身构成实际Seam。
6. 新独立目录/端口验证，不更换正在展示0003的JAR，不写旧服务或现役Milvus集合。真实集成只使用合成文档及显式独立Java集合，不上传业务资料。冻结本切后再接完整答案及多模态，不把局部状态作为总目标终点。

技能选择：codebase-design隐藏事务和完整性复杂度；fullstack-dev保持feature-first Java、现有JWT/session、原生同源fetch、任务polling、统一安全Problem，不引入新SSO/refresh token或CORS架构。索引worker独立进程，生产隔离和迁移/回滚另行验收。

## 2026-09-07 实现中的明确调整

- metadata读回用revision-only过滤后逐行验证scope，刻意发现错误workspace/doc中的额外同revision记录；仍要求完整ID集合相等，不是放宽授权检索范围。真实query检索仍走完整AuthorizedScope。
- 初始化后索引metric/type/state变更有红测复现，verify首尾增加两个index复验并沿用同一绝对deadline，不放宽预算。collection配置与写者冻结是跨HTTP完整性假设，需真实部署验收。
- 独立authority审查发现合成旧active与spec冲突：增加registered_revision_id保留原注册身份，active统一只来自publication。旧identity断言迁至明确字段并增加active=null断言；保留原SQLite值、不可变触发器和所有行为测试，不用删除断言通过门禁。
- 前端独立worker拥有两仓静态app/state/index、索引状态测试以及web精确代理和文档；源码显式同步，后端配置/能力接线与最终浏览器由主线程负责。
- 独立进程审查发现P1：只终止父进程时旧worker可能继续写入；即使worker已终止，上游已接受的HTTP仍可能晚落地。实际父进程kill回归先出现预期1次upsert、实际2次。采用每attempt独立projection generation和physical ID，不再按原计划跨attempt覆盖相同segment ID。原source身份不变，authority原子持久化attempt与完整source→physical→digest entries。早期缺此结构的未发布WIP v3拒绝复用，未修改已发布v1/v2迁移政策。
- protocol v2增加parent PID/startInstant，main watchdog与固定/tmp同用户collection lease协作；仅隔离旧写，不宣称HTTP撤回或OS沙箱。父死亡前尚未发送upsert与已接受旧upsert在新generation receipt后才落地分开测试；后者不是实际authority重启整链验收。108项定向组合通过后，独立审查确认原P1覆盖风险源码层面关闭，另发现同JVM多channel等待者关闭可能释放持有者OS锁的P2，正补跨JVM回归并修复，不预先记PASS。
- 该P2按diagnosing-bugs先建立真实独立JVM竞争的红测再修；最终须重跑完整process/runtime/authority组合与clean verify，不能用同JVM对象仍valid证明OS锁存在。官方平台边界见[JDK 21 FileLock](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html)。
