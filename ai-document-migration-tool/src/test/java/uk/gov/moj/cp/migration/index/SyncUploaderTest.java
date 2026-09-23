package uk.gov.moj.cp.migration.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.azure.core.http.rest.Response;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.models.IndexAction;
import com.azure.search.documents.models.IndexActionType;
import com.azure.search.documents.models.IndexDocumentsBatch;
import com.azure.search.documents.models.IndexDocumentsResult;
import com.azure.search.documents.models.IndexingResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SyncUploaderTest {

    @Test
    void uploadCountsSucceededResults() {
        final SearchClient target = mock(SearchClient.class);
        final AtomicLong succeeded = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        stubUpload(target, result(true), result(true));

        new SyncUploader(target, succeeded, failed).upload(List.of(chunk("id-1"), chunk("id-2")));

        assertThat(succeeded.get()).isEqualTo(2);
        assertThat(failed.get()).isZero();
    }

    @Test
    void uploadCountsFailedResultsWithoutThrowing() {
        final SearchClient target = mock(SearchClient.class);
        final AtomicLong succeeded = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        stubUpload(target, result(true), result(false)); // one per-doc failure

        assertThatCode(() -> new SyncUploader(target, succeeded, failed).upload(List.of(chunk("a"), chunk("b"))))
                .doesNotThrowAnyException(); // per-doc errors are counted, not fatal

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(failed.get()).isEqualTo(1);
    }

    private static IndexingResult result(final boolean succeeded) {
        final IndexingResult result = mock(IndexingResult.class);
        when(result.isSucceeded()).thenReturn(succeeded);
        if (!succeeded) {
            when(result.getKey()).thenReturn("bad-id");
            when(result.getStatusCode()).thenReturn(503);
            when(result.getErrorMessage()).thenReturn("throttled");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void stubUpload(final SearchClient target, final IndexingResult... results) {
        final IndexDocumentsResult batchResult = mock(IndexDocumentsResult.class);
        when(batchResult.getResults()).thenReturn(List.of(results));
        final Response<IndexDocumentsResult> response = mock(Response.class);
        when(response.getValue()).thenReturn(batchResult);
        when(target.indexDocumentsWithResponse(any(), any(), any())).thenReturn(response);
    }

    @Test
    @SuppressWarnings("unchecked")
    void uploadSendsUploadActionsWithClientIdAndMetadataAsMaps() {
        final SearchClient target = mock(SearchClient.class);
        stubUpload(target, result(true));
        final ChunkedEntry doc = ChunkedEntry.builder()
                .id("id-1")
                .clientId("client-a")
                .customMetadata(List.of(new KeyValuePair("caseId", "CASE-1")))
                .build();

        new SyncUploader(target, new AtomicLong(), new AtomicLong()).upload(List.of(doc));

        final ArgumentCaptor<IndexDocumentsBatch> captor = ArgumentCaptor.forClass(IndexDocumentsBatch.class);
        verify(target).indexDocumentsWithResponse(captor.capture(), any(), any());
        final List<IndexAction> actions = captor.getValue().getActions();
        assertThat(actions).hasSize(1);
        final IndexAction action = actions.get(0);
        assertThat(action.getActionType()).isEqualTo(IndexActionType.UPLOAD);
        final Map<String, Object> body = action.getAdditionalProperties();
        // clientId must be stamped on the sync path (was silently dropped before the shared mapper)
        assertThat(body.get("clientId")).isEqualTo("client-a");
        // customMetadata must be plain maps, never KeyValuePair records (silent toString corruption)
        final List<?> metadata = (List<?>) body.get("customMetadata");
        assertThat(metadata).hasSize(1).allMatch(Map.class::isInstance);
        assertThat((Map<String, String>) metadata.get(0))
                .containsEntry("key", "caseId")
                .containsEntry("value", "CASE-1");
    }

    private static ChunkedEntry chunk(final String id) {
        return ChunkedEntry.builder().id(id).build();
    }
}
