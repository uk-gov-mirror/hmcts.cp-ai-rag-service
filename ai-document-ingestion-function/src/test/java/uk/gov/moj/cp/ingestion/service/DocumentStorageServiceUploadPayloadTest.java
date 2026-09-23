package uk.gov.moj.cp.ingestion.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK;
import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK_INDEX;
import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK_VECTOR;
import static uk.gov.moj.cp.ai.index.IndexConstants.CLIENT_ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.CUSTOM_METADATA;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_FILE_URL;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.PAGE_NUMBER;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.models.IndexAction;
import com.azure.search.documents.models.IndexActionType;
import com.azure.search.documents.models.IndexDocumentsBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the exact document body {@code uploadChunks} puts on an {@link IndexAction}.
 *
 * <p>Under v12 the body is an untyped {@code Map<String,Object>} serialized by {@code azure-json}'s
 * {@code writeUntyped}, whose fallback for an unrecognised POJO is {@code writeString(String.valueOf(v))}.
 * Handing it a {@code List<KeyValuePair>} therefore compiles and runs, but indexes the literal
 * {@code "KeyValuePair[key=..., value=...]"} into a {@code Collection(Edm.ComplexType)} field — a
 * runtime indexing failure with no compile-time signal. These assertions are the only cheap guard
 * against that, so they assert on value <em>types</em>, not merely that the SDK was called.
 */
class DocumentStorageServiceUploadPayloadTest {

    private static final int VECTOR_DIMENSIONS = 3072;

    private SearchClient searchClient;
    private DocumentStorageService documentStorageService;

    @BeforeEach
    void setUp() {
        searchClient = mock(SearchClient.class);
        when(searchClient.getIndexName()).thenReturn("test-index");
        documentStorageService = new DocumentStorageService(searchClient);
    }

    @Test
    @DisplayName("upload actions are typed UPLOAD and carry exactly the index schema fields")
    void shouldSendUploadActionsWithExactFieldSet() throws Exception {
        documentStorageService.uploadChunks(List.of(chunk(null)));

        final IndexAction action = captureActions().get(0);
        assertThat(action.getActionType(), is(IndexActionType.UPLOAD));
        assertThat(action.getAdditionalProperties().keySet(), is(Set.of(
                ID, CHUNK, CHUNK_VECTOR, DOCUMENT_FILE_NAME, DOCUMENT_ID,
                PAGE_NUMBER, CHUNK_INDEX, DOCUMENT_FILE_URL, CUSTOM_METADATA)));
    }

    @Test
    @DisplayName("upload actions carry the client column when the chunk is scoped")
    void shouldIncludeClientIdInFieldSet_whenChunkIsScoped() throws Exception {
        documentStorageService.uploadChunks(List.of(chunk("client-a")));

        final IndexAction action = captureActions().get(0);
        assertThat(action.getAdditionalProperties().keySet(), is(Set.of(
                ID, CHUNK, CHUNK_VECTOR, DOCUMENT_FILE_NAME, DOCUMENT_ID,
                PAGE_NUMBER, CHUNK_INDEX, DOCUMENT_FILE_URL, CUSTOM_METADATA, CLIENT_ID)));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("customMetadata is converted to plain maps, never left as KeyValuePair records")
    void shouldConvertCustomMetadataToMaps_notKeyValuePairRecords() throws Exception {
        documentStorageService.uploadChunks(List.of(chunk(null)));

        final Object metadata = captureActions().get(0).getAdditionalProperties().get(CUSTOM_METADATA);
        assertThat(metadata, instanceOf(List.class));

        final List<Object> entries = (List<Object>) metadata;
        // A KeyValuePair here would be silently stringified by azure-json's writeUntyped fallback.
        assertThat(entries, everyItem(instanceOf(Map.class)));
        assertThat(entries, is(List.of(
                Map.of("key", "alpha", "value", "one"),
                Map.of("key", "beta", "value", "two"))));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("chunkVector stays a List<Float> — azure-json writes it natively as a number array")
    void shouldKeepChunkVectorAsFloatList() throws Exception {
        documentStorageService.uploadChunks(List.of(chunk(null)));

        final Object vector = captureActions().get(0).getAdditionalProperties().get(CHUNK_VECTOR);
        assertThat(vector, instanceOf(List.class));
        final List<Object> values = (List<Object>) vector;
        assertThat(values.size(), is(VECTOR_DIMENSIONS));
        assertThat(values.get(0), instanceOf(Float.class));
        assertThat(values.get(0), is(0.1f));
    }

    @Test
    @DisplayName("scalar fields keep their native types — pageNumber/chunkIndex must not become strings")
    void shouldKeepScalarFieldTypes() throws Exception {
        documentStorageService.uploadChunks(List.of(chunk(null)));

        final Map<String, Object> document = captureActions().get(0).getAdditionalProperties();
        assertThat(document.get(ID), instanceOf(String.class));
        assertThat(document.get(PAGE_NUMBER), instanceOf(Integer.class));
        assertThat(document.get(PAGE_NUMBER), is(1));
        assertThat(document.get(CHUNK_INDEX), instanceOf(Integer.class));
        assertThat(document.get(CHUNK_INDEX), is(0));
    }

    // --- vector-dimension guard (FR-5 / AC-006): skip with a WARN, never fail the batch ---

    @Test
    @DisplayName("a null vector is skipped, not uploaded and not fatal")
    void shouldSkipChunkWithNullVector() {
        assertDoesNotThrow(() -> documentStorageService.uploadChunks(List.of(chunkWithVector(null))));

        verify(searchClient, never()).indexDocuments(any(IndexDocumentsBatch.class));
    }

    @Test
    @DisplayName("a wrong-size vector is skipped, not uploaded and not fatal")
    void shouldSkipChunkWithWrongSizeVector() {
        assertDoesNotThrow(() -> documentStorageService.uploadChunks(
                List.of(chunkWithVector(Collections.nCopies(VECTOR_DIMENSIONS - 1, 0.1f)))));

        verify(searchClient, never()).indexDocuments(any(IndexDocumentsBatch.class));
    }

    @Test
    @DisplayName("an invalid chunk is dropped from a mixed batch, the valid one still uploads")
    void shouldUploadOnlyValidChunksFromMixedBatch() throws Exception {
        documentStorageService.uploadChunks(List.of(
                chunkWithVector(null),
                chunk(null),
                chunkWithVector(Collections.nCopies(2, 0.1f))));

        final List<IndexAction> actions = captureActions();
        assertThat(actions.size(), is(1));
        assertThat(actions.get(0).getAdditionalProperties().get(ID), is("chunk-1"));
    }

    private List<IndexAction> captureActions() {
        final ArgumentCaptor<IndexDocumentsBatch> captor = ArgumentCaptor.forClass(IndexDocumentsBatch.class);
        verify(searchClient).indexDocuments(captor.capture());
        return captor.getValue().getActions();
    }

    private ChunkedEntry chunk(final String clientId) {
        return chunkBuilder(Collections.nCopies(VECTOR_DIMENSIONS, 0.1f)).clientId(clientId).build();
    }

    private ChunkedEntry chunkWithVector(final List<Float> vector) {
        return chunkBuilder(vector).id("invalid-chunk").build();
    }

    private ChunkedEntry.Builder chunkBuilder(final List<Float> vector) {
        return ChunkedEntry.builder()
                .id("chunk-1")
                .chunk("some chunk content")
                .chunkVector(vector)
                .documentFileName("doc.pdf")
                .documentId("doc1")
                .pageNumber(1)
                .chunkIndex(0)
                .documentFileUrl("https://example/doc.pdf")
                .customMetadata(List.of(
                        new KeyValuePair("alpha", "one"),
                        new KeyValuePair("beta", "two")));
    }
}
