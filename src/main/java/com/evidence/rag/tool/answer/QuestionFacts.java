package com.evidence.rag.tool.answer;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Bounded question requirements; unsupported grammar never becomes an empty successful plan. */
final class QuestionFacts {
  private static final int MAX_FACTS = 8;
  private static final Pattern SHARED_COLOR = Pattern.compile("^(.+?)(?:分别|各自)(?:是|为)?什么颜色$");
  private static final Pattern COLOR =
      Pattern.compile("^(?:(?:深|浅)?(?:红|蓝|绿|黄|黑|白|灰|紫|橙|棕|褐|粉|青|金|银)色|透明|#[0-9a-fA-F]{3,8})$");
  private static final Pattern PERMISSION_QUESTION =
      Pattern.compile("^(.*?)(?:是否|能否|可否)(?:允许|许可|可以)?(.+)$");
  private static final Pattern ACTION = Pattern.compile("导出|访问|共享|下载|上传|删除|修改|执行|使用");
  private static final Pattern ENGLISH_PERMISSION_QUESTION =
      Pattern.compile("(?i)^(?:is|are) (.+?) (?:allowed|permitted|authorized) to (.+)$");
  private static final Pattern ENGLISH_SUPPORT_QUESTION =
      Pattern.compile("(?i)^does (.+?) support (.+)$");
  private static final Pattern CHINESE_PERMISSION =
      Pattern.compile("^(.*?)(从未被|从未|没有被|没有|未被|不被|并不|不|未)?(?:被)?(?:允许|许可|准许|授权)(.+)$");
  private static final Pattern CHINESE_SUPPORT = Pattern.compile("^(.*?)(不再|没有|不|未)?支持(.+)$");
  private static final Pattern ENGLISH_PERMISSION =
      Pattern.compile(
          "(?i)^(.+?) (?:is|are|was|were) (not )?(?:allowed|permitted|authorized) to (.+)$");
  private static final Pattern ENGLISH_SUPPORT =
      Pattern.compile("(?i)^(.+?) (?:(does not|doesn't|cannot) )?supports? (.+)$");
  private static final Pattern QUANTITY_QUESTION =
      Pattern.compile("^(.*?)(?:是|为)?((?:每人)?(?:每天|每日|每晚|每月|每年))?(多少|多久)(钱|元|天|小时|分钟|秒|次|个)?$");
  private static final Pattern PROCEDURE_SUFFIX =
      Pattern.compile("^(.+?)(?:需要)?(?:怎么|如何)(?:操作|处理|做|进行)?$");
  private static final Pattern PROCEDURE_ACTION =
      Pattern.compile(
          "按|点击|选择|进入|打开|关闭|输入|执行|连接|断开|等待|松开|重启|press|click|select|enter|open|close|type|connect|wait|release",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern REFERENCE_ONLY =
      Pattern.compile("^(?:详?见|参见|参考|请?(?:查看|查阅)|see\\b|refer\\b)", Pattern.CASE_INSENSITIVE);
  private static final Pattern EXCLUDED_NUMERIC_SEQUENCE =
      Pattern.compile("^not\\s+\\p{Nd}++(?:[ \\t]*+[,，][ \\t]*+\\p{Nd}++)++$");

  private QuestionFacts() {}

  static List<Fact> parse(String question) {
    if (question == null || question.isBlank()) {
      return List.of();
    }
    var facts = new LinkedHashSet<Fact>();
    for (String clause : question.split("[？?。！!；;\\r\\n]+")) {
      String stem = clause.strip().replaceFirst("^(?:请问|请说明|请告诉我|告诉我)", "").strip();
      if (stem.isEmpty()) {
        continue;
      }
      Fact procedure = procedureQuestion(stem);
      if (procedure != null) {
        facts.add(procedure);
        if (facts.size() > MAX_FACTS) {
          return List.of();
        }
        continue;
      }
      Fact booleanFact = booleanQuestion(stem);
      if (booleanFact != null) {
        facts.add(booleanFact);
        if (facts.size() > MAX_FACTS) {
          return List.of();
        }
        continue;
      }
      var color = SHARED_COLOR.matcher(stem);
      if (color.matches()) {
        for (String subject : color.group(1).split("以及|[和与及、]")) {
          if (subject.isBlank()) {
            return List.of();
          }
          facts.add(new ColorFact(subject.strip()));
        }
      } else {
        boolean individually = stem.contains("分别") || stem.contains("各自");
        stem =
            stem.replaceFirst(
                "(?i)^(?:please\\s+)?(?:tell\\s+me\\s+)?(?:what|which)\\s+(?:is|are)\\s+(?:the\\s+)?",
                "");
        var quantityQuestion = QUANTITY_QUESTION.matcher(stem);
        Quantity quantity = null;
        if (quantityQuestion.matches()) {
          quantity = new Quantity(quantityQuestion.group(2), quantityQuestion.group(4));
          stem = quantityQuestion.group(1).replaceFirst("(?:分别|各自)$", "").strip();
        } else {
          stem = stem.replaceFirst("(?:分别|各自)?(?:是|为)?(?:什么|谁)$", "").strip();
        }
        String connector = individually ? "以及|同时|还有|并且|[和与及、]" : "以及|同时|还有|并且|、";
        String inheritedSubject = "";
        for (String part : stem.split(connector, -1)) {
          String relation = part.strip();
          if (relation.length() < 2 || relation.matches(".*(?:为什么|为何|如何|怎么|是否|多少|什么).*")) {
            return List.of();
          }
          int possessive = relation.lastIndexOf('的');
          if (possessive >= 0) {
            inheritedSubject = relation.substring(0, possessive + 1);
          } else if (!inheritedSubject.isEmpty()) {
            relation = inheritedSubject + relation;
          }
          facts.add(new ScalarFact(relation, quantity));
        }
      }
      if (facts.size() > MAX_FACTS) {
        return List.of();
      }
    }
    return List.copyOf(new ArrayList<>(facts));
  }

  private static Fact booleanQuestion(String stem) {
    var englishPermission = ENGLISH_PERMISSION_QUESTION.matcher(stem);
    if (englishPermission.matches()) {
      return new BooleanFact(englishPermission.group(1), englishPermission.group(2), false);
    }
    var englishSupport = ENGLISH_SUPPORT_QUESTION.matcher(stem);
    if (englishSupport.matches()) {
      return new BooleanFact(englishSupport.group(1), englishSupport.group(2), true);
    }
    int support = stem.indexOf("是否支持");
    if (support >= 0) {
      return new BooleanFact(stem.substring(0, support), stem.substring(support + 4), true);
    }
    var permission = PERMISSION_QUESTION.matcher(stem);
    if (!permission.matches()) {
      return null;
    }
    String subject = permission.group(1);
    String action = permission.group(2);
    if (subject.isBlank()) {
      var verb = ACTION.matcher(action);
      if (!verb.find()) {
        return null;
      }
      subject = action.substring(0, verb.start());
      action = action.substring(verb.start());
    }
    return new BooleanFact(subject, action, false);
  }

  private static Fact procedureQuestion(String stem) {
    var suffix = PROCEDURE_SUFFIX.matcher(stem);
    String operation = null;
    if (suffix.matches()) {
      operation = suffix.group(1);
    } else if (stem.startsWith("如何") || stem.startsWith("怎么")) {
      operation = stem.substring(2);
    } else if (stem.matches("(?i)^how (?:do i|can i|to) .+")) {
      operation = stem.replaceFirst("(?i)^how (?:do i|can i|to) ", "");
    }
    if (operation == null || operation.length() < 2) {
      return null;
    }
    return new ProcedureFact(operation.replaceFirst("^(?:设备|系统|装置|机器|终端)", ""));
  }

  sealed interface Fact permits ScalarFact, ColorFact, BooleanFact, ProcedureFact {
    String value(String field);

    String requirement();

    default boolean wholeSentence() {
      return false;
    }

    default boolean matches(String field) {
      return value(field) != null;
    }

    default String sha256() {
      return ModelValues.sha256(("fact-v1\n" + requirement()).getBytes(StandardCharsets.UTF_8));
    }
  }

  private record ScalarFact(String relation, Quantity quantity) implements Fact {
    @Override
    public String value(String field) {
      String normalized = normalize(SourceFields.statement(field));
      String key = normalize(relation);
      if (!normalized.startsWith(key)) {
        return null;
      }
      String payload = normalized.substring(key.length()).stripLeading();
      if (!(payload.matches("^(?:是|为|[:：=]).+") || payload.matches("^(?:is|are)\\s+.+"))) {
        return null;
      }
      String value = payload.replaceFirst("^(?:是|为|[:：=]|is\\s+|are\\s+)\\s*", "");
      return REFERENCE_ONLY.matcher(value).find() || (quantity != null && !quantity.accepts(value))
          ? null
          : value;
    }

    @Override
    public boolean matches(String field) {
      String value = value(field);
      // An excluded sequence may veto a conflicting assertion but never supplies its actual
      // value. Ordinary negative states such as "not enabled" remain valid literal answers.
      return value != null && !EXCLUDED_NUMERIC_SEQUENCE.matcher(value).matches();
    }

    @Override
    public String requirement() {
      return "value\n" + relation + "\n" + (quantity == null ? "" : quantity.requirement());
    }
  }

  private record Quantity(String frequency, String unit) {
    boolean accepts(String value) {
      String canonical = normalize(value).replace("每日", "每天");
      if (!canonical.matches(".*[\\p{N}零一二两三四五六七八九十百千万亿].*")) {
        return false;
      }
      if (frequency != null && !canonical.contains(frequency.replace("每日", "每天"))) {
        return false;
      }
      if (unit == null) {
        return true;
      }
      return unit.equals("钱")
          ? canonical.matches(".*(?:元|人民币|美元|欧元|usd|cny|rmb).*")
          : canonical.contains(unit);
    }

    String requirement() {
      return (frequency == null ? "" : frequency) + "\n" + (unit == null ? "" : unit);
    }
  }

  record ProcedureFact(String operation) implements Fact {
    @Override
    public String value(String field) {
      String body = body(field);
      return body == null
              || REFERENCE_ONLY.matcher(body).find()
              || !PROCEDURE_ACTION.matcher(body).find()
          ? null
          : body;
    }

    String body(String field) {
      String statement = normalize(field);
      String key = normalize(operation);
      if (!statement.startsWith(key)) {
        return null;
      }
      String remainder = statement.substring(key.length()).stripLeading();
      if (!remainder.startsWith(":") && !remainder.startsWith("：")) {
        return null;
      }
      return remainder.substring(1).strip();
    }

    @Override
    public String requirement() {
      return "procedure\n" + operation;
    }

    @Override
    public boolean wholeSentence() {
      return true;
    }
  }

  private record ColorFact(String subject) implements Fact {
    @Override
    public String value(String field) {
      String normalized = normalize(SourceFields.statement(field));
      String key = normalize(subject);
      if (!normalized.startsWith(key)) {
        return null;
      }
      String tail = normalized.substring(key.length());
      String payload = tail.replaceFirst("^(?:的颜色)?(?:是|为)", "");
      return !tail.equals(payload) && COLOR.matcher(payload).matches() ? payload : null;
    }

    @Override
    public String requirement() {
      return "color\n" + subject;
    }
  }

  private record BooleanFact(String subject, String action, boolean support) implements Fact {
    @Override
    public String value(String field) {
      String statement = normalize(SourceFields.statement(field));
      var patterns =
          support
              ? List.of(CHINESE_SUPPORT, ENGLISH_SUPPORT)
              : List.of(CHINESE_PERMISSION, ENGLISH_PERMISSION);
      for (var pattern : patterns) {
        var match = pattern.matcher(statement);
        if (!match.matches()) {
          continue;
        }
        String foundSubject = normalize(match.group(1));
        if ((!subject.isBlank() && !normalize(subject).equals(foundSubject))
            || foundSubject.matches(".*(?:不|未|非|仅|只|\\bnot\\b|\\bonly\\b).*")) {
          continue;
        }
        if (normalize(action).equals(normalize(match.group(3)))) {
          return match.group(2) == null ? "true" : "false";
        }
      }
      return null;
    }

    @Override
    public String requirement() {
      return (support ? "support\n" : "permission\n") + subject + "\n" + action;
    }
  }

  private static String normalize(String text) {
    return text.replaceAll("\\s+", " ")
        .strip()
        .toLowerCase(Locale.ROOT)
        .replaceFirst("^the\\s+", "");
  }
}
