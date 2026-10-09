# 0055 知识页主线 acceptance

2026-10-09：本机合成资料与协议替身通过，非真实provider质量评测。最终命令与报告见[verification](verification.md)。

| 场景 | 可观察结果 | 证据 |
|---|---|---|
| WIKI-01 资料编译与人工审阅 | 创建提案不产生正式页，采纳才出现版本1；重启后原内容和来源可读 | WikiWorkspaceHttpTest、WikiWorkspaceServiceTest |
| WIKI-02 来源身份 | 同文不同文件保留独立document/revision/SHA，章节来源来自authority而非模型页码 | duplicateTextInDifferentFilesRetainsBothSourceIdentities；真实HTTP原文页码回读 |
| WIKI-03 更新审阅 | 旧版本不覆盖，竞争提案不能覆盖更新后的页；回滚不留下半个新版本 | versionConflictsDoNotOverwritePageOrEndCompetingProposal、WikiWorkspaceRepositoryTest |
| WIKI-04 原资料撤回 | 已有页stale，来源回读和未采纳提案发布被阻止 | withdrawnSourceMakesPageStaleAndBlocksReadsAndPendingAcceptance |
| WIKI-05 模型协议 | 使用已有OpenAI兼容chat/completions，来源白名单、失败不重试；旧model/index revision不改 | OpenAiCompatibleWikiModelsTest |
| WIKI-06 完整文字与模态边界 | 170段含尾部，不用TopK替代完整编译；文字/OCR/转录/字幕绑定原证据，纯图明确不支持 | WikiCompilationServiceTest |
| WIKI-07 组织共享 | 同组织另一成员可审阅，跨组织不可读写 | organizationMembersShareReviewButOtherOrganizationCannotReadOrWrite |
| WIKI-08 迁移与恢复 | v30→31保留旧行/guards/原件；备份可识别并恢复，重启验证schema | WikiWorkspaceMigrationTest及最终全部MigrationTest |

不以这些结果宣称真实综合问答相关性、跨文件自动知识融合质量、浏览器多模态播放、增量编译或生产已验收。
