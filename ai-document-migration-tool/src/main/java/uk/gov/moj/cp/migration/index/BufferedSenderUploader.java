package uk.gov.moj.cp.migration.index;

import uk.gov.moj.cp.ai.index.SearchFieldMapper;
import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.ArrayList;
import java.util.List;

import com.azure.search.documents.SearchIndexingBufferedSender;
import com.azure.search.documents.models.IndexAction;
import com.azure.search.documents.models.IndexActionType;

/**
 * Async upload path: streams batches into the shared {@link SearchIndexingBufferedSender}, which auto-batches,
 * splits over-16 MB batches, retries throttling (429/503), and flushes in the background. Highest throughput,
 * but it buffers on the producer side — under a tight heap the backlog can grow faster than it flushes.
 *
 * <p>Entries are converted to explicit {@link IndexAction}s here rather than handed to
 * {@code addUploadActions}: that path would serialize {@code ChunkedEntry} with whichever
 * {@code JsonSerializer} the sender defaults to, which is not guaranteed to honour the record's
 * {@code @JsonProperty} names. Building the body via {@link SearchFieldMapper} removes the serializer from
 * the picture entirely and keeps the payload identical to the sync path.
 */
final class BufferedSenderUploader implements DocumentUploader {

    private final SearchIndexingBufferedSender<ChunkedEntry> sender;

    BufferedSenderUploader(final SearchIndexingBufferedSender<ChunkedEntry> sender) {
        this.sender = sender;
    }

    @Override
    public void upload(final List<ChunkedEntry> docs) {
        final List<IndexAction> actions = new ArrayList<>(docs.size());
        for (final ChunkedEntry entry : docs) {
            actions.add(new IndexAction()
                    .setActionType(IndexActionType.UPLOAD)
                    .setAdditionalProperties(SearchFieldMapper.toSearchDocument(entry)));
        }
        sender.addActions(actions);
    }

    @Override
    public void close() {
        sender.close(); // flush all buffered actions and wait for completion
    }
}
