# Plan

先冻结intent/spec/interfaces/REVIEW。三代理仅各自ownership分工，先有意义的行为测试；新的类缺失/编译中间态不计RED，旧有效断言/门禁保持。root统一串行Maven/Spotless，共享target不被代理并发改写。先跑完整正常链，再必要边界/回归/源码绑定、新handoff与独立审计；无真实provider/凭据/Git/旧服务和数据操作。

实际收口：三代理分工与root集成完成；新129项定向通过，首轮1985项全过但branch覆盖失败，补57项有意义边界用例后最终2042项及原80%门禁通过。299前端/73 Node和4单列native通过；collector核对732/46输入与529 native/最终JAR类字节。保留全部失败证据，未配置真实provider、未部署，用户负责页面验收。冻结包和独立审计作为下一交接步骤，非语音声音保留下一主线。
