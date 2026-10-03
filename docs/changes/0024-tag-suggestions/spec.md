# 0024 Specification

## 候选规则

仅使用SynopsisLibraryService按当前ACL、完整publication、原始材料input fingerprint与当前model/policy验证成功的available FileSynopsis。先TERM后TOPIC，各自保持原条目顺序；取完整text.strip()，1..40 Unicode code point、通过ModelValues.label、且不含ASCII/中文逗号或分号。不切句、不截断、不从overview/timeline推测。精确文本去重后最多8条，序号1..N连续。普通大小写不折叠。

候选集合与序号不随现有标签变化；GET同时返回existing_tags。前端只允许选尚未存在的候选；服务器对已存在的所选候选幂等合并，从而保留确认期间其他编辑者新增的标签。候选为空或全部已存在时无新增建议。既有标签语法/20条上限不改变。

纯Tool TagSuggestionCompiler.compile(String synopsisId, FileSynopsis synopsis)返回Domain TagSuggestions：synopsisId, publication, inputFingerprint, modelRevision, synopsisPolicyRevision, suggestionFingerprint, candidates；固定policyRevision=java-synopsis-tags-v1。Candidate为ordinal/tag。指纹绑定策略、全部上述摘要/完整publication身份及有序候选，不含可变existing_tags；SHA256 canonical字段有确定边界。Domain不可变，toString脱敏。Tool不依赖DTO/Repository/模型/HTTP。

## HTTP

仅现有rag.synopsis.enabled有效装配时启用，无新provider配置。Runtime在既有file_synopsis/synopsis_sources启用条件下增加tag_suggestions能力。

GET /v1/documents/{documentId}/tag-suggestions，无query/body：200返回document_id,publication_id,revision_id,source_sha256,synopsis_id,input_fingerprint,model_revision,synopsis_policy_revision,policy_revision,suggestion_fingerprint,existing_tags,can_apply,candidates。candidates为[{ordinal:1,tag:"预算"}]，最多8条。读权限可查看；can_apply只对当前owner/editor为true。无有效当前摘要/资料或失权遵循现有404。

POST /v1/documents/{documentId}/tag-suggestions/apply，仅JSON且无query，精确body {"suggestion_fingerprint":"64位小写SHA256","ordinals":[1,3]}。ordinals是1..8整数，1..8项不重复；拒绝任意客户端标签正文、未知字段和不在当前集合的序号，422。先检查当前owner/editor，再复验当前有效摘要，重算并匹配fingerprint；合法但过期fingerprint为409 tag_suggestions_changed。现有摘要本身不可用/失权沿404。

确认读取、摘要/指纹、当前标签、合并、metadata update和既有hash-only审计在同一个authority事务完成；不能嵌套transaction。复用ManagementService现有append逻辑，保留此刻全部现有标签；合并>20为409 tag_limit_reached且无部分写入。成功200返回既有DocumentResponse。只改tags/updatedAt与审计，不变原文件、源SHA/revision/publication/解析索引，不触发模型。

Service Interface：TagSuggestionService.get(Actor,String)→TagSuggestionResult(TagSuggestions suggestions,List<String> existingTags,boolean canApply)；apply(Actor,String,TagSuggestionApplyCommand)→DocumentResult。ApplyCommand包含suggestionFingerprint/List<Integer> ordinals。Service仅一个外层事务；SynopsisLibraryService提取包内currentSynopsisInTransaction，ManagementService提供包内authorizedDocumentInTransaction(actor,id,requireWrite)及appendTagsInTransaction，不开放SQL或通用事务框架。

## 验收

真实SQLite及Spring HTTP：读取不写→勾选合并→标签筛选/重启保留；资料source/revision/publication不变，当前已有/并发新增标签保留。覆盖完全长条目无建议、Unicode40/41边界、重复/无资格条目、候选顺序/指纹、reader只读、失权/旧摘要或模型身份、过期fingerprint、非法序号和超限原子回滚。审计失败回滚使用现有测试seam，不引入生产SQL访问。

前端真实app DOM+session：当前摘要/身份严格匹配、未知响应拒绝、选择才可保存、先保留整理草稿再动作、迟到GET/POST不能覆盖新资料、保存后列表/标签筛选刷新。部署代理仅增加上述精确GET/POST，无模型长请求例外或新请求体限额。浏览器由用户验收，不把DOM或协议替身写成网页像素/云质量。
