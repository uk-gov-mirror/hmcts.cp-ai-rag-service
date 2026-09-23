package uk.gov.moj.cp.migration.index;

import uk.gov.moj.cp.ai.index.SearchFieldMapper;
import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.models.IndexAction;
import com.azure.search.documents.models.IndexActionType;
import com.azure.search.documents.models.IndexDocumentsBatch;
import com.azure.search.documents.models.IndexDocumentsOptions;
import com.azure.search.documents.models.IndexingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synchronous upload path: uploads each page via the target {@link SearchClient} and blocks until it is
 * indexed before the worker reads the next page. This bounds in-flight memory to {@code workers × pageSize}
 * regardless of heap size (no producer-side backlog), which is what makes the migration safe on a
 * memory-constrained host. Per-document failures are counted (non-fatal), matching the async path; a hard
 * request failure (after SDK retries) propagates and aborts the run via the watchdog.
 */
final class SyncUploader implements DocumentUploader {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncUploader.class);

    private final SearchClient target;
    private final AtomicLong succeeded;
    private final AtomicLong failed;

    SyncUploader(final SearchClient target, final AtomicLong succeeded, final AtomicLong failed) {
        this.target = target;
        this.succeeded = succeeded;
        this.failed = failed;
    }

    @Override
    public void upload(final List<ChunkedEntry> docs) {
        final List<IndexAction> actions = new ArrayList<>(docs.size());
        for (final ChunkedEntry entry : docs) {
            actions.add(new IndexAction()
                    .setActionType(IndexActionType.UPLOAD)
                    .setAdditionalProperties(SearchFieldMapper.toSearchDocument(entry)));
        }
        // setThrowOnAnyError(false): per-doc failures are reported in the results (counted, non-fatal) rather
        // than throwing, mirroring the buffered sender's onActionError handling.
        final var result = target.indexDocumentsWithResponse(new IndexDocumentsBatch(actions),
                new IndexDocumentsOptions().setThrowOnAnyError(false), null).getValue();
        for (final IndexingResult indexed : result.getResults()) {
            if (indexed.isSucceeded()) {
                succeeded.incrementAndGet();
            } else {
                failed.incrementAndGet();
                LOGGER.error("Indexing failed for id={} (status {}): {}",
                        indexed.getKey(), indexed.getStatusCode(), indexed.getErrorMessage());
            }
        }
    }

    @Override
    public void close() {
        // Nothing buffered — each page was uploaded synchronously.
    }
}
