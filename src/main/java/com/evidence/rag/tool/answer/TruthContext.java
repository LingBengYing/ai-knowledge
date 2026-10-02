package com.evidence.rag.tool.answer;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Fail-closed qualifiers and later revisions of the same assertion, not a general inference engine.
 */
final class TruthContext {
  private static final Pattern CONDITIONAL_PREFIX =
      pattern(
          "^若|只有|仅当|仅在|只在|除非|如果|假如|(?:经|待|获).{0,40}(?:批准|审批|授权|许可).{0,16}(?:后|才|方可)|\\bif\\b|only\\s+if|unless|only\\s+after|provided\\s+that|subject\\s+to");
  private static final Pattern QUALIFIER =
      pattern(
          "尚未批准|未经批准|未获批准|仅供参考|暂供讨论|尚非定稿|仍在走会签|待审议|未生效|尚未生效|前提是|取决于|subject\\s+to|pending\\s+approval");
  private static final Pattern SELF_QUALIFIER =
      pattern(
          "(?:并)?(?:不成立|不属实|不准确|不正确|未经核实|尚待核实|只是误传|系误传)(?:[）)]|$)|[（(](?:错误|存疑|误传|假设|incorrect|false|unverified)[）)]|\\b(?:unverified|unconfirmed)\\s+(?:rumou?r|claim)\\b");
  private static final Pattern LIMITED_CLAUSE =
      pattern(
          "^(?:仅(?:适用于|限(?:于)?|对)|只适用于|(?:only\\s+)?during\\b|(?:applicable|applies)\\s+only\\b).+");
  private static final Pattern LIMITED_STATEMENT =
      pattern("仅(?:适用于|限(?:于)?|对)|只适用于|\\b(?:applicable|applies)\\s+only\\b");
  private static final Pattern LEADING_LABEL = pattern("^[\\p{L}\\p{N}_-]{1,24}\\s*[:：]\\s*");
  private static final Pattern REFUTATION =
      pattern(
          "^(?:(?:此|这一|该|其|以上|上述|前述|前文|前款|前项)(?:说法|陈述|内容|信息|结论|数值|金额|预算|命题|事实|答案|消息).{0,32}(?:不|未|无|非|否|误|假|错)|(?:其实|事实上|实际上)(?:并非如此|并不是这样|不是如此|不正确|不对|错误|为假)|(?:this|that|it)\\s+(?:is|was)\\s+(?:not|false|incorrect|wrong|untrue)|the\\s+above\\s+(?:statement|information|amount|value).{0,32}(?:not|no|false|incorrect|wrong)|(?:否|不是|不对|错误|不成立|不正确|不准确|不属实|系误传|no\\b|false\\b|incorrect\\b|wrong\\b))");
  private static final Pattern APPROVAL_CONDITION =
      pattern(
          "^(?:.{0,48}(?:批准|审批|获批|核准|审议|授权|许可)(?:通过)?(?:后|之后|以后|则|才|方可|方能).{0,24}(?:才|方可|方能|生效|执行|有效|实施|适用)|(?:若|如果|假如|只有|仅当|仅在|除非|待).{0,48}(?:批准|审批|获批|核准|授权)|subject\\s+to|only\\s+if|unless|provided\\s+that|是否采纳.{0,16}(?:决定|确定))");
  private static final Pattern CHANGE =
      pattern(
          "不(?:是|准确|正确|成立)|并非|未(?:确定|确认|批准|开放|授权)|尚未|暂供|仅供|尚非|会签|调整为|改为|变更为|更正为|修订为|推翻|否定|取消|撤销|误传|错误|不属实|pending|unknown|unconfirmed|changed|revised|updated|false|revoked|denied|forbidden|disabled");
  private static final Pattern ANAPHOR =
      pattern("^(?:该|此|这一|其|以上|上述|前述|本|相关|这笔|该笔|the|this|that|its)?$");
  private static final Pattern DISCOURSE =
      pattern("^(?:(?:经)?核查|审计结论|结论|其实|实际|事实上|报告)?(?:显示|表明|说明|指出|记载|披露|发现|推翻了|否定了)?");
  private static final List<Pattern> RELATIONS =
      List.of(
          pattern("预算|金额|费用|成本|价格|报价|款项|数字|数值|budget|amount|cost|price"),
          pattern("状态|阶段|进度|status|stage|progress"),
          pattern("名称|名字|姓名|name|title"),
          pattern("识别码|序列号|型号|编号|代码|identifier|code|\\bid\\b"),
          pattern("日期|时间|期限|date|time|deadline"),
          pattern("权限|许可|授权|操作|功能|导出|访问|使用|permission|operation|feature|export|access"));
  private static final Pattern ASSERTION_TAIL =
      pattern(
          "^(?:案)?(?:是|为|并非|仍|尚|不|未|待|暂|仅|调整|改|变更|更正|修订|最终|正式|以|被|已|设|审批|批准|获批|核准|审议|授权|许可|unknown|pending|unconfirmed|is\\b|was\\b|changed|revised|updated|applies\\b|applicable\\b)");
  private static final Pattern REVOKED_REFERENCE =
      pattern("^(?:并)?(?:未开放|未启用|未授权|不允许|禁止|禁用|关闭|撤销|取消|收回|拒绝)(?:了)?(?:此|该|这一|其)$");

  private TruthContext() {}

  static boolean unsafe(String page, SourceFields.Field field, List<SourceFields.Field> fields) {
    if (SourceInstructions.unsafe(page, field, fields)
        || SourceFields.hasNestedAssignment(field.text())
        || QUALIFIER.matcher(field.text()).find()
        || selfRefuted(field.text())
        || CONDITIONAL_PREFIX.matcher(field.text()).find()) {
      return true;
    }
    if (field.end() < page.length() && "？?".indexOf(page.charAt(field.end())) >= 0) {
      return true;
    }
    int sentenceStart = field.start();
    while (sentenceStart > 0 && "。！？!?；;\r\n".indexOf(page.charAt(sentenceStart - 1)) < 0) {
      sentenceStart--;
    }
    for (var preceding : fields) {
      if (preceding.end() > field.start()) {
        continue;
      }
      // Earlier limits bind only the named subject, not references to an assertion yet to appear.
      if ((LIMITED_STATEMENT.matcher(preceding.text()).find()
              || APPROVAL_CONDITION.matcher(preceding.text()).find()
              || QUALIFIER.matcher(preceding.text()).find())
          && !SourceInstructions.unsafe(page, preceding, fields)
          && sameRelationChanged(field.text(), preceding.text(), false)) {
        return true;
      }
      if (preceding.start() >= sentenceStart
          && CONDITIONAL_PREFIX
              .matcher(LEADING_LABEL.matcher(preceding.text()).replaceFirst(""))
              .find()
          && !differentExplicitSubject(field.text(), preceding.text())) {
        return true;
      }
    }
    boolean immediate = true;
    SourceFields.Field previous = null;
    for (var following : fields) {
      if (following.start() <= field.start()) {
        if (following.end() <= field.start()) {
          previous = following;
        }
        continue;
      }
      String tail = following.text().strip().replaceFirst("^(?:但是|不过|然而|可是|但)", "");
      if (immediate
          && !differentExplicitSubject(field.text(), tail)
          && (REFUTATION.matcher(tail).find()
              || APPROVAL_CONDITION.matcher(tail).find()
              || LIMITED_CLAUSE.matcher(tail).find())) {
        return true;
      }
      if (sameRelationChanged(field.text(), tail, true)
          && !SourceInstructions.unsafe(page, following, fields)) {
        return true;
      }
      immediate = false;
    }
    return previous != null && LIMITED_CLAUSE.matcher(previous.text()).find();
  }

  static boolean selfRefuted(String text) {
    return SELF_QUALIFIER.matcher(text).find();
  }

  private static boolean sameRelationChanged(
      String assertion, String context, boolean allowReference) {
    if (!(CHANGE.matcher(context).find()
        || QUALIFIER.matcher(context).find()
        || LIMITED_STATEMENT.matcher(context).find()
        || APPROVAL_CONDITION.matcher(context).find())) {
      return false;
    }
    // Use the same neutral-label normalization as fact matching, without changing source ranges.
    String statement = SourceFields.statement(assertion);
    String revision = SourceFields.statement(context);
    for (Pattern relation : RELATIONS) {
      var original = relation.matcher(statement);
      if (!original.find()) {
        continue;
      }
      String originalSubject = subject(statement.substring(0, original.start()));
      var next = relation.matcher(revision);
      while (next.find()) {
        String relationPrefix = revision.substring(0, next.start());
        String newSubject = subject(relationPrefix);
        String nextTail = revision.substring(next.end()).stripLeading();
        boolean revokedReference = REVOKED_REFERENCE.matcher(newSubject).matches();
        boolean relatedSubject =
            newSubject.equals(originalSubject)
                || (allowReference && (ANAPHOR.matcher(newSubject).matches() || revokedReference));
        boolean scopedChange =
            ASSERTION_TAIL.matcher(nextTail).find()
                || (relationPrefix.contains("推翻") || relationPrefix.contains("否定"))
                || revokedReference;
        if (relatedSubject && scopedChange) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean differentExplicitSubject(String assertion, String context) {
    var conditional = CONDITIONAL_PREFIX.matcher(LEADING_LABEL.matcher(context).replaceFirst(""));
    if (conditional.find() && conditional.start() == 0) {
      // An "only if B ..." premise may constrain A; a different named premise is not independent.
      return false;
    }
    for (var relation : RELATIONS) {
      var original = relation.matcher(SourceFields.statement(assertion));
      var other = relation.matcher(context);
      if (!original.find() || !other.find()) {
        continue;
      }
      String originalPrefix =
          SourceFields.statement(assertion).substring(0, original.start()).strip();
      String otherPrefix = context.substring(0, other.start()).strip();
      if (possessiveSubject(originalPrefix) && possessiveSubject(otherPrefix)) {
        String originalSubject = subject(originalPrefix);
        String otherSubject = subject(otherPrefix);
        return !ANAPHOR.matcher(otherSubject).matches() && !originalSubject.equals(otherSubject);
      }
    }
    return false;
  }

  private static boolean possessiveSubject(String prefix) {
    return prefix.endsWith("的") || prefix.endsWith("'s") || prefix.endsWith("’s");
  }

  private static String subject(String text) {
    return DISCOURSE
        .matcher(text.strip().toLowerCase(Locale.ROOT))
        .replaceFirst("")
        .replaceFirst("^(?:the|this|that)\\s+", "")
        .replaceFirst("的$", "")
        .strip();
  }

  private static Pattern pattern(String expression) {
    return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }
}
