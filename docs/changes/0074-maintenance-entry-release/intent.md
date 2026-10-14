# 意图

2026-10-12，负责人要求部署最新代码。拉取后端main f4fe26d、前端main 74664fc，无本地未提交改动/冲突；范围是维护期间会话检查、503响应/只读等待及跨站页面导航，不增加模型调用。

调用链：LibraryOperationGate/LibraryOperationFilter → entry backendJson/proxy → Web createApi；页面导航在entry浏览器边界单独判定。Java/前端配套发布，Agent/OCR/原文/索引/模型配置/免登录不改。
