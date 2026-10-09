package com.evidence.rag.model.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Derived, reviewed navigation content with a server-bound source for every section. */
public record WikiContent(String title, String kind, List<Section> sections) {
  public record Source(
      String id, PublicationVersion publication, FileSynopsis.Reference reference) {
    public Source {
      ModelValues.identifier(id, 128);
      if (publication == null || reference == null) {
        throw ModelValues.invalid();
      }
    }

    @Override
    public String toString() {
      return "WikiContent.Source[redacted]";
    }
  }

  public record Section(String id, String heading, String body, List<Source> sources) {
    public Section {
      ModelValues.identifier(id, 128);
      ModelValues.label(heading, 200);
      if (body == null
          || body.isBlank()
          || body.codePoints()
              .anyMatch(
                  c ->
                      (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                          || (c >= 0xD800 && c <= 0xDFFF))
          || sources == null
          || sources.isEmpty()
          || sources.stream().anyMatch(source -> source == null)
          || sources.stream().map(Source::id).distinct().count() != sources.size()) {
        throw ModelValues.invalid();
      }
      sources = List.copyOf(sources);
    }

    @Override
    public String toString() {
      return "WikiContent.Section[redacted]";
    }
  }

  public WikiContent {
    ModelValues.label(title, 200);
    if (kind == null
        || !Set.of("topic", "entity", "procedure", "overview").contains(kind)
        || sections == null
        || sections.isEmpty()
        || sections.stream().anyMatch(section -> section == null)) {
      throw ModelValues.invalid();
    }
    var ids = new HashSet<String>();
    var bindings = new HashMap<String, Source>();
    for (var section : sections) {
      if (!ids.add(section.id())) {
        throw ModelValues.invalid();
      }
      for (var source : section.sources()) {
        var previous = bindings.putIfAbsent(source.id(), source);
        if (previous != null && !previous.equals(source)) {
          throw ModelValues.invalid();
        }
      }
    }
    sections = List.copyOf(sections);
  }

  @Override
  public String toString() {
    return "WikiContent[redacted]";
  }
}
