package uk.gov.moj.cp.migration.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.index.SearchFieldMapper;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;

import java.util.Collections;
import java.util.List;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchPagedIterable;
import com.azure.search.documents.models.SearchPagedResponse;
import com.azure.search.documents.models.SearchResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class IndexCopierTest {

    @Test
    void formatEtaReturnsUnknownBeforeAnyProgressIsMeasurable() {
        assertThat(IndexCopier.formatEta(0, 100, 1_000_000_000L)).isEqualTo("unknown");
        assertThat(IndexCopier.formatEta(100, 100, 0)).isEqualTo("unknown");
    }

    @Test
    void formatEtaProjectsThisRunThroughputOverRemainingRecords() {
        assertThat(IndexCopier.formatEta(100, 100, 100_000_000_000L)).isEqualTo("00:01:40");
        assertThat(IndexCopier.formatEta(1, 7200, 1_000_000_000L)).isEqualTo("02:00:00");
        assertThat(IndexCopier.formatEta(500, 0, 5_000_000_000L)).isEqualTo("00:00:00");
    }

    @Test
    void copyAllDocumentsUploadsEachPageAndAdvancesCursorUntilPartialPage() {
        final DocumentUploader uploader = mock(DocumentUploader.class);
        final IndexCopier copier = spy(new IndexCopier(mock(SearchClient.class), uploader, 2, 1, 0));

        final List<ChunkedEntry> page1 = List.of(chunk("id-1"), chunk("id-2")); // full page -> continue
        final List<ChunkedEntry> page2 = List.of(chunk("id-3"));                // partial page -> stop
        doReturn(page1).doReturn(page2).when(copier).readPage(any(), any());

        final long submitted = copier.copyAllDocuments(null);

        assertThat(submitted).isEqualTo(3);

        // Cursor: first page starts at null, second resumes after the last id of page 1.
        final ArgumentCaptor<String> cursor = ArgumentCaptor.forClass(String.class);
        final InOrder order = inOrder(copier, uploader);
        order.verify(copier).readPage(any(), cursor.capture());
        order.verify(uploader).upload(page1);
        order.verify(copier).readPage(any(), cursor.capture());
        order.verify(uploader).upload(page2);
        assertThat(cursor.getAllValues()).containsExactly(null, "id-2");
    }

    @Test
    void copyAllDocumentsStopsAtTheMaxRecordsCapAndTrimsTheLastPage() {
        final DocumentUploader uploader = mock(DocumentUploader.class);
        final IndexCopier copier = spy(new IndexCopier(mock(SearchClient.class), uploader, 2, 1, 3));

        final ChunkedEntry c1 = chunk("id-1");
        final ChunkedEntry c2 = chunk("id-2");
        final ChunkedEntry c3 = chunk("id-3");
        final ChunkedEntry c4 = chunk("id-4");
        doReturn(List.of(c1, c2)).doReturn(List.of(c3, c4)).when(copier).readPage(any(), any());

        final long submitted = copier.copyAllDocuments(null);

        assertThat(submitted).isEqualTo(3); // capped at maxRecords=3
        verify(uploader).upload(List.of(c1, c2)); // first full page
        verify(uploader).upload(List.of(c3));     // second page trimmed to the remaining budget (1)
    }

    @Test
    void copyAllDocumentsSkipsChunksWithMissingOrWrongSizeVectorBeforeUploading() {
        final DocumentUploader uploader = mock(DocumentUploader.class);
        // pageSize (5) > page (3) so the single page is "partial" and the read loop terminates after it.
        final IndexCopier copier = spy(new IndexCopier(mock(SearchClient.class), uploader, 5, 1, 0));

        final ChunkedEntry valid = chunk("id-1");                              // 3072-dim vector
        final ChunkedEntry wrongSize = chunkWithVector("id-2", List.of(0.0f)); // too short
        final ChunkedEntry nullVector = chunkWithVector("id-3", null);         // missing
        doReturn(List.of(valid, wrongSize, nullVector)).when(copier).readPage(any(), any());

        final long submitted = copier.copyAllDocuments(null);

        assertThat(submitted).isEqualTo(1);
        verify(uploader).upload(List.of(valid)); // only the valid chunk is uploaded
    }

    @Test
    void copyAllDocumentsUploadsNothingWhenSourceIsEmpty() {
        final DocumentUploader uploader = mock(DocumentUploader.class);
        final IndexCopier copier = spy(new IndexCopier(mock(SearchClient.class), uploader, 2, 1, 0));

        doReturn(List.of()).when(copier).readPage(any(), any());

        final long submitted = copier.copyAllDocuments(null);

        assertThat(submitted).isZero();
        verifyNoInteractions(uploader);
    }

    @Test
    void readPageAppliesTheShardFilterAndMapsResultsToChunkedEntries() {
        final SearchClient source = mock(SearchClient.class);
        final ChunkedEntry c1 = chunk("id-a");
        final ChunkedEntry c2 = chunk("id-b");
        // v12 removes SearchResult.getDocument(Class); the raw document body arrives untyped.
        final SearchPagedIterable results = mock(SearchPagedIterable.class);
        when(results.iterator()).thenReturn(List.of(searchResult(c1), searchResult(c2)).iterator());
        final ArgumentCaptor<SearchOptions> options = ArgumentCaptor.forClass(SearchOptions.class);
        when(source.search(options.capture())).thenReturn(results);

        final List<ChunkedEntry> page =
                new IndexCopier(source, mock(DocumentUploader.class), 500, 1, 0).readPage(new Shard("a", "b", null), "a5");

        assertThat(page).containsExactly(c1, c2);
        // shard-bounded keyset query issued; v12 carries the query text on the options
        assertThat(options.getValue().getSearchText()).isEqualTo("*");
        assertThat(options.getValue().getFilter()).isNotNull();
    }

    private static SearchResult searchResult(final ChunkedEntry entry) {
        return new SearchResult().setAdditionalProperties(SearchFieldMapper.toSearchDocument(entry));
    }

    @Test
    void copyAllDocumentsAbortsAndCancelsWorkersWhenAShardFails() {
        final IndexCopier copier = spy(new IndexCopier(mock(SearchClient.class), mock(DocumentUploader.class), 2, 1, 0));
        doThrow(new RuntimeException("read boom")).when(copier).readPage(any(), any());

        assertThatThrownBy(() -> copier.copyAllDocuments(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("worker failed");
    }

    @Test
    void copyAllDocumentsCountsAlreadyProcessedDocsWhenResumingASingleWorkerRun() {
        final SearchClient source = mock(SearchClient.class);
        // v12 moves the total count off the iterable onto the page.
        final SearchPagedResponse countPage = mock(SearchPagedResponse.class);
        when(countPage.getCount()).thenReturn(42L);
        final SearchPagedIterable countResult = mock(SearchPagedIterable.class);
        when(countResult.iterableByPage()).thenReturn(List.of(countPage));
        when(source.search(any())).thenReturn(countResult);
        final IndexCopier copier = spy(new IndexCopier(source, mock(DocumentUploader.class), 2, 1, 0));
        doReturn(List.of()).when(copier).readPage(any(), any()); // nothing left to copy from the cursor

        final long submitted = copier.copyAllDocuments("cursor-x");

        assertThat(submitted).isZero();
        verify(source).search(any()); // resume count query executed (countProcessedBefore)
    }

    @Test
    @SuppressWarnings("unchecked")
    void copyAllDocumentsStampsEveryUploadedChunkWithTheConfiguredClientIdWhenOverrideSet() {
        final DocumentUploader uploader = mock(DocumentUploader.class);
        final String override = "consumer-abc";
        final IndexCopier copier = spy(new IndexCopier(mock(SearchClient.class), uploader, 5, 1, 0, override));

        final ChunkedEntry source1 = populatedChunk("id-1");
        final ChunkedEntry source2 = populatedChunk("id-2");
        doReturn(List.of(source1, source2)).when(copier).readPage(any(), any());

        copier.copyAllDocuments(null);

        final ArgumentCaptor<List<ChunkedEntry>> uploaded = ArgumentCaptor.forClass(List.class);
        verify(uploader).upload(uploaded.capture());
        // The copy carries the override clientId; every other field is unchanged from the source (record equality).
        assertThat(uploaded.getValue())
                .allSatisfy(entry -> assertThat(entry.clientId()).isEqualTo(override))
                .containsExactly(withClientId(source1, override), withClientId(source2, override));
    }

    @Test
    @SuppressWarnings("unchecked")
    void copyAllDocumentsLeavesClientIdUntouchedWhenNoOverrideConfigured() {
        final DocumentUploader uploader = mock(DocumentUploader.class);
        // Convenience constructor (no override) — the pre-existing verbatim copy behaviour.
        final IndexCopier copier = spy(new IndexCopier(mock(SearchClient.class), uploader, 5, 1, 0));

        final ChunkedEntry source1 = populatedChunk("id-1");
        doReturn(List.of(source1)).when(copier).readPage(any(), any());

        copier.copyAllDocuments(null);

        final ArgumentCaptor<List<ChunkedEntry>> uploaded = ArgumentCaptor.forClass(List.class);
        verify(uploader).upload(uploaded.capture());
        assertThat(uploaded.getValue()).containsExactly(source1); // verbatim
        assertThat(uploaded.getValue().get(0).clientId()).isNull();
    }

    @Test
    void copyAllDocumentsStampsTheSamePageIdenticallyOnEveryRun() {
        final String override = "consumer-abc";
        final ChunkedEntry source1 = populatedChunk("id-1");
        final ChunkedEntry source2 = populatedChunk("id-2");

        final List<ChunkedEntry> firstRun = uploadedFor(override, List.of(source1, source2));
        final List<ChunkedEntry> secondRun = uploadedFor(override, List.of(source1, source2));

        // Re-processing the same page yields byte-identical stamped copies, so a re-run is a safe idempotent upsert.
        assertThat(firstRun).isEqualTo(secondRun);
        assertThat(firstRun).containsExactly(withClientId(source1, override), withClientId(source2, override));
    }

    @SuppressWarnings("unchecked")
    private static List<ChunkedEntry> uploadedFor(final String override, final List<ChunkedEntry> page) {
        final DocumentUploader uploader = mock(DocumentUploader.class);
        final IndexCopier copier = spy(new IndexCopier(mock(SearchClient.class), uploader, 5, 1, 0, override));
        doReturn(page).when(copier).readPage(any(), any());
        copier.copyAllDocuments(null);
        final ArgumentCaptor<List<ChunkedEntry>> uploaded = ArgumentCaptor.forClass(List.class);
        verify(uploader).upload(uploaded.capture());
        return uploaded.getValue();
    }

    private static ChunkedEntry chunk(final String id) {
        return chunkWithVector(id, Collections.nCopies(IndexCopier.VECTOR_DIMENSIONS, 0.0f));
    }

    private static ChunkedEntry chunkWithVector(final String id, final List<Float> vector) {
        return ChunkedEntry.builder().id(id).chunkVector(vector).build();
    }

    /** A chunk with every field populated, a valid-size vector, and no clientId set (as a legacy source row). */
    private static ChunkedEntry populatedChunk(final String id) {
        return ChunkedEntry.builder()
                .id(id)
                .documentId("doc-" + id)
                .chunk("content of " + id)
                .chunkVector(Collections.nCopies(IndexCopier.VECTOR_DIMENSIONS, 0.1f))
                .documentFileName("file-" + id + ".pdf")
                .pageNumber(2)
                .chunkIndex(4)
                .documentFileUrl("https://store/file-" + id + ".pdf")
                .customMetadata(List.of(new KeyValuePair("k", "v")))
                .build();
    }

    /** The source chunk with only its clientId replaced — every other field preserved. */
    private static ChunkedEntry withClientId(final ChunkedEntry source, final String clientId) {
        return ChunkedEntry.builder()
                .id(source.id())
                .documentId(source.documentId())
                .chunk(source.chunk())
                .chunkVector(source.chunkVector())
                .documentFileName(source.documentFileName())
                .pageNumber(source.pageNumber())
                .chunkIndex(source.chunkIndex())
                .documentFileUrl(source.documentFileUrl())
                .customMetadata(source.customMetadata())
                .clientId(clientId)
                .build();
    }
}
