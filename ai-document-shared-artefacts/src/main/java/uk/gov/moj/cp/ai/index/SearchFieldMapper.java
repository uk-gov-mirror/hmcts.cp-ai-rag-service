package uk.gov.moj.cp.ai.index;

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
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The single {@link ChunkedEntry} &lt;-&gt; search-document conversion shared by ingestion, retrieval and
 * the migration tool. From azure-search-documents 12 the SDK has no typed document layer: a document body
 * is a plain {@code Map<String,Object>} on an {@code IndexAction}, and a hit's body comes back as
 * {@code SearchResult.getAdditionalProperties()}.
 *
 * <p>Two conversions in here are load-bearing and fail silently if skipped:
 * <ul>
 *   <li><b>Write:</b> {@code customMetadata} must be a list of plain maps. Document bodies are written by
 *       {@code azure-json}'s {@code writeUntyped}, whose fallback for an unrecognised POJO is
 *       {@code writeString(String.valueOf(value))} — a {@link KeyValuePair} record would be indexed as the
 *       literal {@code "KeyValuePair[key=..., value=...]"} into a {@code Collection(Edm.ComplexType)} field.
 *       That compiles, and only fails when the service rejects the batch.</li>
 *   <li><b>Read:</b> JSON numbers arrive as {@code Double}; {@code chunkVector} has to be narrowed back to
 *       {@code List<Float>} because it feeds the containment/MMR cosine maths. Jackson does the narrowing
 *       as part of binding to the record.</li>
 * </ul>
 */
public final class SearchFieldMapper {

    /**
     * Lenient on purpose: a read is routinely a partial {@code $select} projection, and a hit may carry
     * service-side fields the record does not model.
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SearchFieldMapper() {
    }

    /**
     * Builds the document body for an index action from a chunk. The client-scoping column is emitted only
     * when the chunk carries one — writing it unconditionally would break indexing against a live index
     * that does not define the field.
     */
    public static Map<String, Object> toSearchDocument(final ChunkedEntry entry) {
        final Map<String, Object> document = new LinkedHashMap<>();
        document.put(ID, entry.id());
        document.put(CHUNK, entry.chunk());
        document.put(CHUNK_VECTOR, entry.chunkVector());
        document.put(DOCUMENT_FILE_NAME, entry.documentFileName());
        document.put(DOCUMENT_ID, entry.documentId());
        document.put(PAGE_NUMBER, entry.pageNumber());
        document.put(CHUNK_INDEX, entry.chunkIndex());
        document.put(DOCUMENT_FILE_URL, entry.documentFileUrl());
        document.put(CUSTOM_METADATA, toMetadataMaps(entry.customMetadata()));
        if (!isNullOrEmpty(entry.clientId())) {
            document.put(CLIENT_ID, entry.clientId());
        }
        return document;
    }

    /**
     * Maps a raw search-document body (a hit's additional properties) onto a {@link ChunkedEntry}. Fields
     * absent from the projection are left null.
     */
    public static ChunkedEntry toChunkedEntry(final Map<String, Object> document) {
        return OBJECT_MAPPER.convertValue(document, ChunkedEntry.class);
    }

    /** {@code customMetadata} as plain maps — see the class comment for why the record cannot be passed through. */
    private static List<Map<String, String>> toMetadataMaps(final List<KeyValuePair> customMetadata) {
        if (customMetadata == null) {
            return null;
        }
        final List<Map<String, String>> maps = new ArrayList<>(customMetadata.size());
        for (final KeyValuePair pair : customMetadata) {
            maps.add(Map.of("key", pair.key(), "value", pair.value()));
        }
        return maps;
    }
}
