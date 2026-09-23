package uk.gov.moj.cp.ai.index;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract for the shared {@code ChunkedEntry} &lt;-&gt; {@code Map<String,Object>} conversion that
 * ingestion, retrieval and the migration tool all go through once v12 removes {@code SearchDocument}
 * and {@code SearchResult.getDocument(Class)}.
 *
 * <p>Two invariants, both of which fail silently rather than loudly if broken:
 * <ol>
 *   <li><b>Write:</b> {@code KeyValuePair} records must become plain maps. {@code azure-json}'s
 *       {@code writeUntyped} stringifies an unrecognised POJO, so the record would be indexed as
 *       {@code "KeyValuePair[key=..., value=...]"} into a complex-type field.</li>
 *   <li><b>Read:</b> JSON numbers arrive as {@code Double}; {@code chunkVector} must be narrowed
 *       back to {@code List<Float>}, since it feeds the containment/MMR cosine maths.</li>
 * </ol>
 */
class SearchFieldMapperTest {

    @Test
    @DisplayName("toSearchDocument converts customMetadata records to plain maps")
    void shouldConvertCustomMetadataRecordsToMaps() {
        final Map<String, Object> document = SearchFieldMapper.toSearchDocument(entry("client-a"));

        assertThat(document.get(CUSTOM_METADATA), instanceOf(List.class));
        @SuppressWarnings("unchecked") final List<Object> metadata = (List<Object>) document.get(CUSTOM_METADATA);
        assertThat(metadata, everyItem(instanceOf(Map.class)));
        assertThat(metadata, is(List.of(Map.of("key", "alpha", "value", "one"))));
    }

    @Test
    @DisplayName("toSearchDocument emits every schema field, and the client column only when scoped")
    void shouldEmitSchemaFields() {
        assertThat(SearchFieldMapper.toSearchDocument(entry("client-a")).keySet(), is(Set.of(
                ID, CHUNK, CHUNK_VECTOR, DOCUMENT_FILE_NAME, DOCUMENT_ID,
                PAGE_NUMBER, CHUNK_INDEX, DOCUMENT_FILE_URL, CUSTOM_METADATA, CLIENT_ID)));

        assertThat(SearchFieldMapper.toSearchDocument(entry(null)).keySet(), is(Set.of(
                ID, CHUNK, CHUNK_VECTOR, DOCUMENT_FILE_NAME, DOCUMENT_ID,
                PAGE_NUMBER, CHUNK_INDEX, DOCUMENT_FILE_URL, CUSTOM_METADATA)));
    }

    @Test
    @DisplayName("toChunkedEntry narrows JSON Doubles in the vector back to Float")
    void shouldNarrowVectorDoublesToFloat() {
        final ChunkedEntry mapped = SearchFieldMapper.toChunkedEntry(Map.of(
                ID, "chunk-1",
                CHUNK_VECTOR, List.of(0.25d, -0.5d, 1.0d)));

        assertThat(mapped.chunkVector(), is(List.of(0.25f, -0.5f, 1.0f)));
        assertThat(mapped.chunkVector().get(0), instanceOf(Float.class));
    }

    @Test
    @DisplayName("toChunkedEntry maps every schema field and tolerates a partial $select projection")
    void shouldMapEveryFieldAndTolerateMissingOnes() {
        final ChunkedEntry mapped = SearchFieldMapper.toChunkedEntry(Map.of(
                ID, "chunk-1",
                DOCUMENT_ID, "doc-1",
                CHUNK, "chunk text",
                DOCUMENT_FILE_NAME, "doc.pdf",
                PAGE_NUMBER, 7,
                CHUNK_INDEX, 3,
                DOCUMENT_FILE_URL, "https://example/doc.pdf",
                CUSTOM_METADATA, List.of(Map.of("key", "alpha", "value", "one"))));

        assertThat(mapped.id(), is("chunk-1"));
        assertThat(mapped.documentId(), is("doc-1"));
        assertThat(mapped.chunk(), is("chunk text"));
        assertThat(mapped.documentFileName(), is("doc.pdf"));
        assertThat(mapped.pageNumber(), is(7));
        assertThat(mapped.chunkIndex(), is(3));
        assertThat(mapped.documentFileUrl(), is("https://example/doc.pdf"));
        assertThat(mapped.customMetadata(), is(List.of(new KeyValuePair("alpha", "one"))));
        // Not in the $select projection (the unscoped read path omits it) — must not blow up.
        assertThat(mapped.clientId(), is(nullValue()));
        assertThat(mapped.chunkVector(), is(nullValue()));
    }

    @Test
    @DisplayName("round-trips an entry through the map form without losing or re-typing anything")
    void shouldRoundTrip() {
        final ChunkedEntry original = entry("client-a");

        assertThat(SearchFieldMapper.toChunkedEntry(SearchFieldMapper.toSearchDocument(original)), is(original));
    }

    private static ChunkedEntry entry(final String clientId) {
        return ChunkedEntry.builder()
                .id("chunk-1")
                .documentId("doc-1")
                .chunk("chunk text")
                .chunkVector(List.of(0.25f, -0.5f, 1.0f))
                .documentFileName("doc.pdf")
                .pageNumber(7)
                .chunkIndex(3)
                .documentFileUrl("https://example/doc.pdf")
                .customMetadata(List.of(new KeyValuePair("alpha", "one")))
                .clientId(clientId)
                .build();
    }
}
