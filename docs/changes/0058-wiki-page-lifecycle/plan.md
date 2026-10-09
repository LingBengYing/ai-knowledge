# 执行

Backend worker独占Wiki Controller/Service/Repository/DTO、独立schema迁移与相关测试。Frontend worker独占Wiki客户端/工作台/精确代理与相关测试，不改变问答行为。root维护工件、联调运行与真实页面验证。各自保留其他任务改动；不对运行target打包。

先直接相关用例失败再实现通过；本地新版真实HTTP及浏览器用新建合成知识页走删除/恢复，原始资料不变。不调用模型，不用测试数量替代页面可用，不扩展彻底清空/批量清理。
