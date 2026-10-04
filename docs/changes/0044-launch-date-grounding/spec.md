# 契约

支持完整英文问题 `When does <subject> launch?` 和 `When did <subject> launch?`。主体须明确；未支持的否定或条件问法不裁去条件后作答。本切不增加其它谓词、未知语言或通用日期语义推断。

事实只能由同主体的原句 `<subject> launch|launches|launched [on] <完整英文月份> <日>[,] <四位年>` 支持。日历日期必须合法。主体按既有大小写及空白规范比较，日期的规范值仅用于冲突比较；源文字、SHA、原 quote 与 CP offset 保持不变。日期原句必须被模型的精确原文 quote 完整覆盖，完整多问题覆盖与现有指令、条件、否定及冲突判定继续适用。

日与年之间的原逗号已由现有 SourceFields 保存，不另改字段切分。音频中其它词语或数字不能补足日期或预算。历史原件、转录、trace 不改。文字证明 policy 为 `java-text-grounding-v8-launch-dates`。
