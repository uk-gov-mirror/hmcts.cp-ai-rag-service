package uk.gov.moj.cp.migration.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.azure.core.http.rest.Response;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.models.IndexDocumentsResult;
import com.azure.search.documents.models.IndexingResult;
import org.junit.jupiter.api.Test;

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

    private static ChunkedEntry chunk(final String id) {
        return ChunkedEntry.builder().id(id).build();
    }
}
