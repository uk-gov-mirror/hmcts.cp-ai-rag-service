package uk.gov.moj.cp.ingestion.service;

import static java.lang.String.format;
import static java.util.Objects.isNull;
import static java.util.stream.Collectors.joining;
import static uk.gov.moj.cp.ai.index.IndexConstants.CLIENT_ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.CUSTOM_METADATA;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.FALSE_VALUE;
import static uk.gov.moj.cp.ai.index.IndexConstants.ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.IS_ACTIVE;
import static uk.gov.moj.cp.ai.util.StringUtil.escapeODataStringLiteral;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.client.AISearchClientFactory;
import uk.gov.moj.cp.ai.index.SearchFieldMapper;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.models.IndexAction;
import com.azure.search.documents.models.IndexActionType;
import com.azure.search.documents.models.IndexDocumentsBatch;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchPagedIterable;
import com.azure.search.documents.models.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentStorageService.class);
    private final SearchClient searchClient;
    private final String indexName;

    public static final int VECTOR_DIMENSIONS = 3072;

    public DocumentStorageService(String endpoint, String indexName) {
        if (isNullOrEmpty(endpoint) || isNullOrEmpty(indexName)) {
            throw new IllegalArgumentException("Document Storage Endpoint and Vector Index Name cannot be null or empty");
        }

        LOGGER.info("Connecting to Azure AI Search endpoint '{}' and index '{}'", endpoint, indexName);

        this.indexName = indexName;

        this.searchClient = AISearchClientFactory.getInstance(endpoint, indexName);

        LOGGER.info("Initialized Azure AI Search client with managed identity.");
    }

    public DocumentStorageService(final SearchClient searchClient) {
        if (isNull(searchClient)) {
            throw new IllegalArgumentException("Document Storage searchClient cannot be null");
        }
        this.indexName = searchClient.getIndexName();
        this.searchClient = searchClient;
    }

    public void uploadChunks(List<ChunkedEntry> chunks) throws DocumentProcessingException {
        LOGGER.info("Uploading {} chunks to Azure Search Index: {}", chunks.size(), indexName);

        try {
            final List<IndexAction> actions = new ArrayList<>(chunks.size());

            for (ChunkedEntry chunkedEntry : chunks) {
                if (chunkedEntry.chunkVector() == null || chunkedEntry.chunkVector().size() != VECTOR_DIMENSIONS) {
                    LOGGER.warn("Skipping invalid embedding for page {} (vector size: {})",
                            chunkedEntry.pageNumber(),
                            chunkedEntry.chunkVector() != null ? chunkedEntry.chunkVector().size() : null);
                    continue;
                }

                // SearchFieldMapper owns the field names and, critically, converts customMetadata records
                // into plain maps — the untyped document body would otherwise stringify them.
                actions.add(new IndexAction()
                        .setActionType(IndexActionType.UPLOAD)
                        .setAdditionalProperties(SearchFieldMapper.toSearchDocument(chunkedEntry)));
            }

            if (!actions.isEmpty()) {
                searchClient.indexDocuments(new IndexDocumentsBatch(actions));
                LOGGER.info("Batch upload successful for index {}", indexName);
            } else {
                LOGGER.warn("No valid chunks found to upload for index {}", indexName);
            }

        } catch (Exception e) {
            final String errorMessage = "Failed to upload list of chunks to Azure Search index " + indexName;
            LOGGER.error(errorMessage, e);
            throw new DocumentProcessingException(errorMessage, e);
        }
    }

    @SuppressWarnings("unchecked")
    public void markDocumentsInActive(final String clientId, final List<String> supersededDocuments) {
        final List<IndexAction> allUpdates = new ArrayList<>();

        final SearchPagedIterable searchResults = getSearchResults(clientId, supersededDocuments);

        for (SearchResult result : searchResults) {
            final Map<String, Object> searchDocument = result.getAdditionalProperties();
            if (searchDocument == null) {
                continue; // a hit with no document fields cannot be merged (no id to address)
            }

            // Copied, not appended to in place: the retrieved body may be immutable.
            final List<Map<String, String>> customMetadata = searchDocument.containsKey(CUSTOM_METADATA)
                    ? new ArrayList<>((List<Map<String, String>>) searchDocument.get(CUSTOM_METADATA))
                    : new ArrayList<>();
            Map<String, String> isActiveKeyValue = new HashMap<>();
            isActiveKeyValue.put("key", IS_ACTIVE);
            isActiveKeyValue.put("value", FALSE_VALUE);
            customMetadata.add(isActiveKeyValue);

            allUpdates.add(mergeAction(searchDocument, customMetadata));
        }

        if (!allUpdates.isEmpty()) {
            searchClient.indexDocuments(new IndexDocumentsBatch(allUpdates));
        }
    }

    SearchPagedIterable getSearchResults(final String clientId, final List<String> supersededDocuments) {
        final String documentFilter = supersededDocuments.stream()
                .map(id -> format("%s/any(m: m/key eq '%s' and m/value eq '%s')", CUSTOM_METADATA, DOCUMENT_ID, id))
                .collect(joining(" or "));

        // When a client id is supplied, scope the supersede match to that client by leading with a
        // client-equality clause and grouping the document clauses, so one client cannot mark
        // another client's chunks inactive. When it is null/empty the filter is unchanged.
        final String filter = isNullOrEmpty(clientId)
                ? documentFilter
                : format("%s eq '%s' and (%s)", CLIENT_ID, escapeODataStringLiteral(clientId), documentFilter);

        LOGGER.info("Find search results matching filter criteria: {}", filter);

        final SearchOptions options = new SearchOptions()
                .setSearchText("*")
                .setFilter(filter)
                .setSelect(ID, CUSTOM_METADATA);

        return searchClient.search(options);
    }

    /**
     * A partial update: the action names only the key and the metadata collection, so every other field is
     * left untouched service-side. Naming any other field here would overwrite it with this projection.
     */
    private static IndexAction mergeAction(final Map<String, Object> doc, final List<Map<String, String>> metadata) {
        return new IndexAction()
                .setActionType(IndexActionType.MERGE)
                .setAdditionalProperties(Map.of(ID, doc.get(ID), CUSTOM_METADATA, metadata));
    }

}