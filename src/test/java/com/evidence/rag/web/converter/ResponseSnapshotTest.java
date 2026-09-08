package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.model.dto.FolderListResult;
import com.evidence.rag.model.dto.FolderResult;
import com.evidence.rag.model.vo.DocumentActionResponse;
import com.evidence.rag.model.vo.DocumentActionsResponse;
import com.evidence.rag.model.vo.DocumentPageResponse;
import com.evidence.rag.model.vo.DocumentResponse;
import com.evidence.rag.model.vo.MediaInfoResponse;
import com.evidence.rag.model.vo.RuntimeResponse;
import com.evidence.rag.model.vo.TagListResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResponseSnapshotTest {
  @Test
  void directlyExposedFolderDtoHasADefensiveListEnvelope() {
    var folder = new FolderResult("folder", "Synthetic", 0L, true);
    var items = new ArrayList<>(List.of(folder));
    var response = new FolderListResult(items);
    assertSnapshot(items, List.of(folder), response.items());
    assertThrows(NullPointerException.class, () -> new FolderListResult(null));
  }

  @Test
  void documentCollectionsAreDefensiveSnapshots() {
    var modalities = new ArrayList<>(List.of("text"));
    var tags = new ArrayList<>(List.of("synthetic"));
    var response = document(modalities, tags);
    assertSnapshot(modalities, List.of("text"), response.modalities());
    assertSnapshot(tags, List.of("synthetic"), response.tags());
  }

  @Test
  void documentPageIsADefensiveSnapshot() {
    var document = document(List.of(), List.of());
    var items = new ArrayList<>(List.of(document));
    var response = new DocumentPageResponse(items, 1, 1, 20, 1);
    assertSnapshot(items, List.of(document), response.items());
  }

  @Test
  void documentActionReceiptsAreADefensiveSnapshot() {
    var action = new DocumentActionResponse("document", false, null, "not_found", "unavailable");
    var items = new ArrayList<>(List.of(action));
    var response = new DocumentActionsResponse(items);
    assertSnapshot(items, List.of(action), response.items());
  }

  @Test
  void tagListIsADefensiveSnapshot() {
    var items = new ArrayList<>(List.of("synthetic"));
    var response = new TagListResponse(items);
    assertSnapshot(items, List.of("synthetic"), response.items());
  }

  @Test
  void runtimeCapabilityListsAreDefensiveSnapshots() {
    var capabilities = new ArrayList<>(List.of("management"));
    var unavailable = new ArrayList<>(List.of("answers"));
    var response =
        new RuntimeResponse(
            "development_headers",
            "org-main",
            "java",
            "management_slice",
            capabilities,
            unavailable);
    assertSnapshot(capabilities, List.of("management"), response.capabilities());
    assertSnapshot(unavailable, List.of("answers"), response.unavailable());
  }

  @Test
  void collectionFieldsRequireExplicitListsRatherThanNullableSnapshots() {
    assertAll(
        () -> assertThrows(NullPointerException.class, () -> document(null, List.of())),
        () -> assertThrows(NullPointerException.class, () -> document(List.of(), null)),
        () ->
            assertThrows(
                NullPointerException.class, () -> new DocumentPageResponse(null, 0, 1, 20, 0)),
        () -> assertThrows(NullPointerException.class, () -> new DocumentActionsResponse(null)),
        () -> assertThrows(NullPointerException.class, () -> new TagListResponse(null)),
        () ->
            assertThrows(
                NullPointerException.class,
                () ->
                    new RuntimeResponse(
                        "jwt", "org-main", "java", "management_slice", null, List.of())),
        () ->
            assertThrows(
                NullPointerException.class,
                () ->
                    new RuntimeResponse(
                        "jwt", "org-main", "java", "management_slice", List.of(), null)));
  }

  private static <T> void assertSnapshot(List<T> input, List<T> expected, List<T> snapshot) {
    input.clear();
    assertAll(
        () -> assertEquals(expected, snapshot),
        () -> assertThrows(UnsupportedOperationException.class, snapshot::clear));
  }

  private static DocumentResponse document(List<String> modalities, List<String> tags) {
    return new DocumentResponse(
        "document",
        "synthetic.txt",
        "ready",
        null,
        "registered-revision",
        0,
        "updated",
        modalities,
        new MediaInfoResponse("text/plain", 1L, "a".repeat(64)),
        "Synthetic",
        null,
        null,
        tags,
        "owner",
        true,
        false,
        false,
        false,
        "not_indexed",
        null,
        null,
        false,
        null,
        true,
        "document");
  }
}
