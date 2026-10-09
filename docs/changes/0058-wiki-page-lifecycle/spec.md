# 行为合同

- GET `/v1/wiki/pages` 增加可选 `state=active|deleted`，默认active；列表和total在分页前按生命周期过滤。
- 页DTO增加 `state`（active/deleted）与 `lifecycle_version`（安全整数，未操作初始0）。当前/历史详情保留读取并标注当前生命周期，原文读取维持既有来源校验。
- DELETE `/v1/wiki/pages/{id}?version=N&lifecycle_version=L` 无请求体，版本为当前内容版本；POST `/v1/wiki/pages/{id}/restore` JSON `{version:N,lifecycle_version:L}`。都返回最新WikiPageResult。
- 同组织成员可操作；内容版本和生命周期版本双CAS，不符409；不存在/跨组织404。删除active→deleted，恢复deleted→active，生命周期版本每次加1。不得物理删原件、知识页版本或索引。
- 已删除知识页不能创建更新提案或采纳已有更新提案，直至明确恢复；删除、恢复不调用模型。不可变版本表/触发器保持，新增独立生命周期表和正式迁移。
- 新临时库验证删除/隐藏/恢复/历史/原件不变、重启及版本冲突。当前本机预览可更新运行包，先备份并保留旧包，不触及线上。
