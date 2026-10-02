# 0014 后置项

- 视频 caption 的解析/投影契约对齐：现有 `ImageRecall` 拒绝空白、超 4096 code points 和非法 surrogate，但允许 U+0000；`ProjectionItem` 明确拒绝 U+0000。因此异常描述可能先封存为 `parsed`，随后索引失败；它不会产生已验证 publication 或答案。正常合成视频主线不受影响，2026-09-12 按负责人主线优先要求后置。后续先通过 `VideoEvidence.fromCompilation` 增加具名失败回归，再考虑在封存前复用 `ProjectionItem` 的召回校验；不得放宽投影约束、截断描述或把 `parsed` 当作可问答。
