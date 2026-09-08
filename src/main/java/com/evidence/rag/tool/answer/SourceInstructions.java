package com.evidence.rag.tool.answer;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Source instructions and examples are data, not assertions authorizing an ordinary factual answer.
 */
final class SourceInstructions {
  private static final Pattern INSTRUCTION =
      pattern(
          "忽略.{0,40}(?:指令|提示|规则|要求)|(?:不管|无论).{0,40}(?:问|问题).{0,24}(?:回答|输出)|(?:泄露|泄漏|输出|透露).{0,24}(?:密码|密钥|凭据)|ignore.{0,64}(?:instructions?|prompts?|rules?)|reveal.{0,24}(?:credentials|passwords?|secrets?)|<\\s*/?\\s*(?:system|assistant|developer)\\b|\\[(?:system|assistant|developer)\\]");
  private static final Pattern DIRECTIVE_LABEL =
      pattern(
          "^(?:请.{0,24}(?:回答|输出|改成)|必须回答|回答|输出|示例|样例|假设|虚构|错误)\\s*[:：]|^(?:请把|请将).{0,24}(?:答案|回复).{0,16}[:：]");
  private static final Pattern NEXT_STATEMENT =
      pattern(
          "(?:回答|输出).{0,24}(?:下面|以下)|(?:下面|以下).{0,24}(?:回答|输出)|(?:always\\s+)?answer.{0,24}(?:following|next)");
  private static final Pattern EXAMPLE_SUFFIX =
      pattern(
          "^(?:这|那)(?:只)?是(?:示例|例子|假设|虚构)|^(?:this|that)\\s+is\\s+(?:only\\s+)?(?:an?\\s+)?(?:example|hypothesis|fiction)");
  private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

  private SourceInstructions() {}

  static boolean unsafe(String page, SourceFields.Field field, List<SourceFields.Field> fields) {
    if (INSTRUCTION.matcher(field.text()).find()
        || DIRECTIVE_LABEL.matcher(field.text()).find()
        || insideFence(page, field.start())) {
      return true;
    }
    int start = field.start();
    while (start > 0 && "。！？!?；;\r\n".indexOf(page.charAt(start - 1)) < 0) {
      start--;
    }
    if (INSTRUCTION.matcher(page.substring(start, field.start())).find()) {
      return true;
    }
    SourceFields.Field previous = null;
    for (var other : fields) {
      if (other.end() <= field.start()) {
        previous = other;
      } else if (other.start() > field.end()) {
        if (EXAMPLE_SUFFIX.matcher(other.text()).find()) {
          return true;
        }
        break;
      }
    }
    return previous != null && NEXT_STATEMENT.matcher(previous.text()).find();
  }

  private static boolean insideFence(String page, int position) {
    char fenceCharacter = 0;
    int fenceWidth = 0;
    int cursor = 0;
    while (cursor < position) {
      int end = page.indexOf('\n', cursor);
      if (end < 0 || end >= position) {
        break;
      }
      var fence = FENCE.matcher(page.substring(cursor, end));
      if (fence.matches()) {
        String marker = fence.group(1);
        if (fenceCharacter == 0) {
          fenceCharacter = marker.charAt(0);
          fenceWidth = marker.length();
        } else if (marker.charAt(0) == fenceCharacter
            && marker.length() >= fenceWidth
            && fence.group(2).isBlank()) {
          fenceCharacter = 0;
          fenceWidth = 0;
        }
      }
      cursor = end + 1;
    }
    return fenceCharacter != 0;
  }

  private static Pattern pattern(String expression) {
    return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }
}
