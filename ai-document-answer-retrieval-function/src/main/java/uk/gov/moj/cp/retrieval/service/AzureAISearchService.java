package uk.gov.moj.cp.retrieval.service;

import static java.lang.String.format;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_SEARCH_SERVICE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_SEARCH_SERVICE_INDEX_NAME;
import static uk.gov.moj.cp.ai.index.IndexConstants.CUSTOM_METADATA;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;
import static uk.gov.moj.cp.ai.util.StringUtil.escapeLuceneSpecialChars;
import static uk.gov.moj.cp.ai.util.StringUtil.escapeODataStringLiteral;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.client.AISearchClientFactory;
import uk.gov.moj.cp.ai.index.IndexConstants;
import uk.gov.moj.cp.ai.index.SearchFieldMapper;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.retrieval.exception.SearchServiceException;
import uk.gov.moj.cp.retrieval.service.filter.ContentContainmentService;
import uk.gov.moj.cp.retrieval.service.filter.DeduplicationService;
import uk.gov.moj.cp.retrieval.service.filter.DiversificationService;

import java.util.ArrayList;
import java.util.List;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.models.QueryType;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchPagedIterable;
import com.azure.search.documents.models.SearchResult;
import com.azure.search.documents.models.VectorizedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AzureAISearchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzureAISearchService.class);
    private final SearchClient searchClient;
    private final ContentContainmentService contentContainmentService;
    private final DeduplicationService deduplicationService;
    private final DiversificationService diversificationService;

    private final int nearestNeighborsCount;
    private final int topResultsCount;

    private final String IS_ACTIVE_FILTER = format("(not %s/any(m: m/key eq 'is_active') or %s/any(m: m/key eq 'is_active' and m/value ne 'false'))",
            CUSTOM_METADATA, CUSTOM_METADATA);

    public AzureAISearchService() {
        this(getRequiredEnv(AZURE_SEARCH_SERVICE_ENDPOINT),
                getRequiredEnv(AZURE_SEARCH_SERVICE_INDEX_NAME));
    }

    public AzureAISearchService(final String endpoint, final String searchIndexName) {

        if (isNullOrEmpty(endpoint) || isNullOrEmpty(searchIndexName)) {
            throw new IllegalArgumentException("Azure AI Search endpoint and index name must be set as environment variables.");
        }

        nearestNeighborsCount = getRequiredEnvAsInteger("SEARCH_NEAREST_NEIGHBOURS_COUNT", "50");
        topResultsCount = getRequiredEnvAsInteger("SEARCH_TOP_RESULTS_COUNT", "50");

        LOGGER.info("Search parameters set as - Nearest Neighbors: {}, Top Results: {}", nearestNeighborsCount, topResultsCount);

        this.searchClient = AISearchClientFactory.getInstance(endpoint, searchIndexName);
        this.contentContainmentService = new ContentContainmentService();
        this.deduplicationService = new DeduplicationService();
        this.diversificationService = new DiversificationService();

        LOGGER.info("Initialized Azure AI Search client with managed identity.");

    }

    public List<ChunkedEntry> search(
            final String clientId,
            final String userQuery,
            final List<Float> vectorizedUserQuery,
            final List<KeyValuePair> metadataFilters) throws SearchServiceException {

        if (isNullOrEmpty(userQuery) || null == vectorizedUserQuery || vectorizedUserQuery.isEmpty() || null == metadataFilters || metadataFilters.isEmpty()) {
            throw new IllegalArgumentException("Search Query or Metadata Filters are null or empty");
        }

        LOGGER.info("Retrieving documents for query with filters: {}", metadataFilters);

        final String filterExpression = generateFilterExpression(clientId, metadataFilters);
        LOGGER.info("Retrieving documents for query with filters: {}", filterExpression);


        // 2. Define VectorQuery for semantic search
        final VectorizedQuery vectorizedQuery = new VectorizedQuery(
                vectorizedUserQuery.stream().collect(ArrayList::new, ArrayList::add, ArrayList::addAll)
        )
                .setKNearestNeighbors(nearestNeighborsCount) // Number of nearest neighbors to retrieve
                .setFields(IndexConstants.CHUNK_VECTOR);

        // 3. Define SearchOptions
        final SearchOptions searchOptions = new SearchOptions()
                .setSearchText(escapeLuceneSpecialChars(userQuery)) // Keyword leg of the hybrid query
                .setFilter(filterExpression) // Apply the OData filter
                .setVectorQueries(vectorizedQuery) // Add the vector query
                .setQueryType(QueryType.FULL) // Use SEMANTIC for hybrid search with semantic ranking
                .setSelect(getColumnsToRetrieve(clientId)) // Select all fields needed for LLM context and citation
                .setTop(topResultsCount); // Number of top results to return after filtering and ranking


        // 4. Execute the search
        try {
            final SearchPagedIterable searchResults = searchClient.search(searchOptions);

            final List<ChunkedEntry> chunkedEntries = new ArrayList<>();
            for (final SearchResult result : searchResults) {
                // Get the full document map for each chunk
                chunkedEntries.add(SearchFieldMapper.toChunkedEntry(result.getAdditionalProperties()));
            }
            LOGGER.info("Successfully retrieved {}  documents from Azure AI Search.", chunkedEntries.size());

            // Pipeline: information-safe containment dedup first, then (optional) semantic dedup,
            // then MMR as a final relevance-vs-diversity / token-budget pass.
            final List<ChunkedEntry> containmentDedupedEntries = contentContainmentService.deduplicateByContainment(chunkedEntries);
            final List<ChunkedEntry> dedupedEntries = deduplicationService.performSemanticDeduplication(containmentDedupedEntries);
            return diversificationService.diversify(vectorizedUserQuery, dedupedEntries);

        } catch (Exception e) {
            // Implement retry logic here if needed
            throw new SearchServiceException("Failed to retrieve documents from Azure AI Search", e);
        }
    }

    String generateFilterExpression(final String clientId, final List<KeyValuePair> metadataFilters) {
        final StringBuilder filterBuilder = new StringBuilder();

        // When a client id is supplied, lead with a non-optional equality clause on the top-level
        // client field, and-joined with the metadata clauses and the security-trimming trailer. When
        // it is null/empty the expression is byte-for-byte identical to the unscoped output.
        if (!isNullOrEmpty(clientId)) {
            filterBuilder.append(format("%s eq '%s'", IndexConstants.CLIENT_ID, escapeODataStringLiteral(clientId)));
        }

        if (metadataFilters != null && !metadataFilters.isEmpty()) {
            for (KeyValuePair pair : metadataFilters) {
                String key = pair.key();
                String value = pair.value();
                if (!filterBuilder.isEmpty()) {
                    filterBuilder.append(" and ");
                }
                // Use 'any' operator for Collection(Edm.ComplexType).
                // Both key and value are escaped per the OData v4 string-literal grammar
                // (single quote -> two single quotes) to prevent filter breakage on legitimate
                // apostrophes and OData injection via untrusted caller input.
                filterBuilder.append(format("%s/any(m: m/key eq '%s' and m/value eq '%s')",
                        CUSTOM_METADATA,
                        escapeODataStringLiteral(key),
                        escapeODataStringLiteral(value)));
            }
        }

        if (!filterBuilder.isEmpty()) {
            filterBuilder.append(" and ");
        }

        // Add is_active != 'false'
        filterBuilder.append(IS_ACTIVE_FILTER);

        return filterBuilder.toString();
    }


    String[] getColumnsToRetrieve(final String clientId) {
        // The chunk vector is always retrieved; whether it is used for cosine comparisons is decided
        // by the downstream services (DeduplicationService / DiversificationService) based on their
        // own toggles. This search service stays agnostic of those toggles.
        // The client column is selected only when a client scope is supplied: Azure AI Search rejects
        // a $select naming a field the index does not define, and the field only exists once the
        // rebuilt index is live — the same condition under which a client scope starts arriving.
        final List<String> columns = new ArrayList<>(List.of(
                IndexConstants.ID,
                IndexConstants.CHUNK,
                IndexConstants.DOCUMENT_FILE_NAME,
                IndexConstants.DOCUMENT_ID,
                IndexConstants.PAGE_NUMBER,
                IndexConstants.DOCUMENT_FILE_URL,
                CUSTOM_METADATA,
                IndexConstants.CHUNK_VECTOR));
        if (!isNullOrEmpty(clientId)) {
            columns.add(4, IndexConstants.CLIENT_ID);
        }
        return columns.toArray(new String[0]);
    }

}