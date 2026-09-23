package uk.gov.moj.cp.migration.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static uk.gov.moj.cp.ai.index.IndexConstants.CUSTOM_METADATA;
import static uk.gov.moj.cp.ai.index.IndexConstants.ID;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.azure.search.documents.SearchIndexingBufferedSender;
import com.azure.search.documents.models.IndexAction;
import com.azure.search.documents.models.IndexActionType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BufferedSenderUploaderTest {

    /**
     * The uploader builds explicit {@link IndexAction}s rather than using {@code addUploadActions}: that
     * keeps the document body out of the sender's own serializer, which is not guaranteed to honour the
     * {@code ChunkedEntry} record's {@code @JsonProperty} names.
     */
    @Test
    @SuppressWarnings("unchecked")
    void uploadSendsExplicitUploadActionsAndCloseFlushesTheSender() {
        final SearchIndexingBufferedSender<ChunkedEntry> sender = mock(SearchIndexingBufferedSender.class);
        final BufferedSenderUploader uploader = new BufferedSenderUploader(sender);
        final List<ChunkedEntry> docs = List.of(ChunkedEntry.builder()
                .id("x")
                .customMetadata(List.of(new KeyValuePair("k", "v")))
                .build());

        uploader.upload(docs);
        uploader.close();

        final ArgumentCaptor<Collection<IndexAction>> actions = ArgumentCaptor.forClass(Collection.class);
        verify(sender).addActions(actions.capture());
        assertThat(actions.getValue()).singleElement().satisfies(action -> {
            assertThat(action.getActionType()).isEqualTo(IndexActionType.UPLOAD);
            assertThat(action.getAdditionalProperties()).containsEntry(ID, "x");
            // Plain maps, never KeyValuePair records — an unrecognised POJO would be stringified on the wire.
            assertThat(action.getAdditionalProperties().get(CUSTOM_METADATA))
                    .isEqualTo(List.of(Map.of("key", "k", "value", "v")));
        });
        verify(sender).close();
    }
}
