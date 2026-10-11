package com.evidence.rag.client.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Incremental display-only JSON string extraction, never a replacement for final JSON validation.
 */
final class AgentAnswerPreview {
  private final Consumer<String> sink;
  private final List<Frame> stack = new ArrayList<>();
  private final StringBuilder text = new StringBuilder();
  private boolean inString;
  private boolean key;
  private boolean selected;
  private boolean escaped;
  private boolean primitive;
  private int unicodeRemaining;
  private int unicode;
  private char pendingHigh;
  private int statementCount;

  AgentAnswerPreview(Consumer<String> sink) {
    this.sink = sink;
  }

  void accept(String part) {
    var output = new StringBuilder();
    for (int i = 0; i < part.length(); i++) {
      char c = part.charAt(i);
      if (inString) {
        if (unicodeRemaining > 0) {
          int digit = Character.digit(c, 16);
          if (digit < 0) {
            throw invalid();
          }
          unicode = unicode * 16 + digit;
          if (--unicodeRemaining == 0) {
            decoded((char) unicode, output);
          }
        } else if (escaped) {
          escaped = false;
          if (c == 'u') {
            unicodeRemaining = 4;
            unicode = 0;
          } else {
            decoded(
                switch (c) {
                  case '"', '\\', '/' -> c;
                  case 'b' -> '\b';
                  case 'f' -> '\f';
                  case 'n' -> '\n';
                  case 'r' -> '\r';
                  case 't' -> '\t';
                  default -> throw invalid();
                },
                output);
          }
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          if (pendingHigh != 0) {
            throw invalid();
          }
          inString = false;
          if (key) {
            stack.getLast().key = text.toString();
            stack.getLast().expectingKey = false;
          } else {
            endValue();
          }
        } else {
          if (c < 32) {
            throw invalid();
          }
          decoded(c, output);
        }
        continue;
      }
      if (primitive) {
        if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
          primitive = false;
          endValue();
        } else {
          continue;
        }
      }
      if (Character.isWhitespace(c) || c == ':') {
        continue;
      }
      if (c == '"') {
        inString = true;
        key = !stack.isEmpty() && !stack.getLast().array && stack.getLast().expectingKey;
        List<String> path = key ? List.of() : valuePath();
        selected =
            path.size() == 4
                && path.get(0).equals("result")
                && path.get(1).equals("statements")
                && path.get(2).matches("[0-9]+")
                && path.get(3).equals("text");
        text.setLength(0);
        if (selected && statementCount++ > 0) {
          output.append("\n\n");
        }
      } else if (c == '{' || c == '[') {
        if (stack.size() >= 32) {
          throw invalid();
        }
        stack.add(new Frame(c == '[', valuePath()));
      } else if (c == '}' || c == ']') {
        if (stack.isEmpty() || stack.getLast().array != (c == ']')) {
          throw invalid();
        }
        stack.removeLast();
        endValue();
      } else if (c == ',') {
        if (stack.isEmpty()) {
          throw invalid();
        }
        var frame = stack.getLast();
        if (!frame.array) {
          frame.expectingKey = true;
          frame.key = null;
        }
      } else {
        primitive = true;
      }
    }
    if (!output.isEmpty()) {
      sink.accept(output.toString());
    }
  }

  private void decoded(char c, StringBuilder output) {
    if (pendingHigh != 0) {
      if (!Character.isLowSurrogate(c)) {
        throw invalid();
      }
      text.append(pendingHigh).append(c);
      if (selected) {
        output.append(pendingHigh).append(c);
      }
      pendingHigh = 0;
    } else if (Character.isHighSurrogate(c)) {
      pendingHigh = c;
    } else {
      if (Character.isLowSurrogate(c) || c == 0) {
        throw invalid();
      }
      text.append(c);
      if (selected) {
        output.append(c);
      }
    }
  }

  private List<String> valuePath() {
    if (stack.isEmpty()) {
      return List.of();
    }
    var frame = stack.getLast();
    var path = new ArrayList<>(frame.path);
    if (!frame.array && frame.key == null) {
      throw invalid();
    }
    path.add(frame.array ? Integer.toString(frame.index) : frame.key);
    return List.copyOf(path);
  }

  private void endValue() {
    if (!stack.isEmpty() && stack.getLast().array) {
      stack.getLast().index++;
    }
  }

  private static TextModels.Failure invalid() {
    return new TextModels.Failure("model_invalid_response");
  }

  private static final class Frame {
    final boolean array;
    final List<String> path;
    boolean expectingKey;
    String key;
    int index;

    Frame(boolean array, List<String> path) {
      this.array = array;
      this.path = path;
      expectingKey = !array;
    }
  }
}
