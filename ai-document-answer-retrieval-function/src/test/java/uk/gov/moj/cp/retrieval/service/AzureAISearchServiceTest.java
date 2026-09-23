package uk.gov.moj.cp.retrieval.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.index.IndexConstants.CUSTOM_METADATA;

import uk.gov.moj.cp.ai.client.AISearchClientFactory;
import uk.gov.moj.cp.ai.index.IndexConstants;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.retrieval.exception.SearchServiceException;
import uk.gov.moj.cp.retrieval.service.filter.DeduplicationService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.models.QueryType;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchPagedIterable;
import com.azure.search.documents.models.SearchResult;
import com.azure.search.documents.models.VectorQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class AzureAISearchServiceTest {

    private SearchClient mockSearchClient;
    private DeduplicationService mockDeduplicationService;
    private AzureAISearchService service;
    private MockedStatic<AISearchClientFactory> clientFactoryMockedStatic;

    private final String endpoint = "https://fake-search-service.search.windows.net";
    private final String indexName = "fake-index";

    @BeforeEach
    void setUp() {
        mockSearchClient = mock(SearchClient.class);
        mockDeduplicationService = mock(DeduplicationService.class);
        clientFactoryMockedStatic = mockStatic(AISearchClientFactory.class);
        clientFactoryMockedStatic.when(() -> AISearchClientFactory.getInstance(anyString(), anyString())).thenReturn(mockSearchClient);
        service = new AzureAISearchService(endpoint, indexName);
    }

    @AfterEach
    void tearDown() {
        if (null != clientFactoryMockedStatic) {
            clientFactoryMockedStatic.close();
        }
    }

    @Test
    @DisplayName("Throws exception when userQuery is null or empty")
    void throwsExceptionWhenUserQueryIsNullOrEmpty() {
        final List<Float> vector = Arrays.asList(1.0f, 2.0f);
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "v"));
        assertThrows(IllegalArgumentException.class, () -> service.search(null, null, vector, filters));
        assertThrows(IllegalArgumentException.class, () -> service.search(null, "", vector, filters));
    }

    @Test
    @DisplayName("Throws exception when vectorizedUserQuery is null or empty")
    void throwsExceptionWhenVectorizedUserQueryIsNullOrEmpty() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "v"));
        assertThrows(IllegalArgumentException.class, () -> service.search(null, "query", null, filters));
        assertThrows(IllegalArgumentException.class, () -> service.search(null, "query", Collections.emptyList(), filters));
    }

    @Test
    @DisplayName("Throws exception when metadataFilters is null or empty")
    void throwsExceptionWhenMetadataFiltersIsNullOrEmpty() {
        final List<Float> vector = Arrays.asList(1.0f, 2.0f);
        assertThrows(IllegalArgumentException.class, () -> service.search(null, "query", vector, null));
        assertThrows(IllegalArgumentException.class, () -> service.search(null, "query", vector, Collections.emptyList()));
    }

    @Test
    @DisplayName("Returns deduplicated results from search")
    void returnsDeduplicatedResultsFromSearch() throws SearchServiceException {
        final String userQuery = "query";
        final List<Float> vector = Arrays.asList(1.0f, 2.0f);
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "v"));
        final SearchPagedIterable mockPagedIterable = mock(SearchPagedIterable.class);
        // v12 removes SearchResult.getDocument(Class); the raw body arrives via getAdditionalProperties.
        final SearchResult result = searchResult(Map.of(IndexConstants.ID, "id"));
        when(mockPagedIterable.iterator()).thenReturn(List.of(result).iterator());
        when(mockSearchClient.search(any(SearchOptions.class))).thenReturn(mockPagedIterable);
        final List<ChunkedEntry> results = service.search(null, userQuery, vector, filters);
        when(mockDeduplicationService.performSemanticDeduplication(anyList())).thenReturn(results);
        assertEquals(1, results.size());
        assertEquals("id", results.get(0).id());
    }

    @Test
    @DisplayName("Throws SearchServiceException on search client failure")
    void throwsSearchServiceExceptionOnSearchClientFailure() {
        final List<Float> vector = Arrays.asList(1.0f, 2.0f);
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "v"));
        when(mockSearchClient.search(any(SearchOptions.class))).thenThrow(new RuntimeException("fail"));
        assertThrows(SearchServiceException.class, () -> service.search(null, "query", vector, filters));
    }

    @Test
    @DisplayName("Maps the untyped result body onto ChunkedEntry, narrowing JSON numbers back to Float")
    void mapsAdditionalPropertiesOntoChunkedEntry() throws SearchServiceException {
        // JSON numbers deserialize as Double; ChunkedEntry.chunkVector is List<Float> and feeds the
        // containment/MMR cosine maths, so the narrowing must survive the v11 -> v12 mapping change.
        final SearchResult result = searchResult(Map.of(
                IndexConstants.ID, "chunk-1",
                IndexConstants.DOCUMENT_ID, "doc-1",
                IndexConstants.CHUNK, "chunk text",
                IndexConstants.DOCUMENT_FILE_NAME, "doc.pdf",
                IndexConstants.PAGE_NUMBER, 7,
                IndexConstants.CHUNK_INDEX, 3,
                IndexConstants.DOCUMENT_FILE_URL, "https://example/doc.pdf",
                IndexConstants.CHUNK_VECTOR, List.of(0.25d, -0.5d, 1.0d),
                CUSTOM_METADATA, List.of(Map.of("key", "alpha", "value", "one"))));

        final SearchPagedIterable mockPagedIterable = mock(SearchPagedIterable.class);
        when(mockPagedIterable.iterator()).thenReturn(List.of(result).iterator());
        when(mockSearchClient.search(any(SearchOptions.class))).thenReturn(mockPagedIterable);

        final ChunkedEntry entry = service.search(
                null, "query", List.of(1.0f), List.of(new KeyValuePair("k", "v"))).get(0);

        assertEquals("chunk-1", entry.id());
        assertEquals("doc-1", entry.documentId());
        assertEquals("chunk text", entry.chunk());
        assertEquals("doc.pdf", entry.documentFileName());
        assertEquals(7, entry.pageNumber());
        assertEquals(3, entry.chunkIndex());
        assertEquals("https://example/doc.pdf", entry.documentFileUrl());
        assertEquals(List.of(0.25f, -0.5f, 1.0f), entry.chunkVector());
        assertEquals(List.of(new KeyValuePair("alpha", "one")), entry.customMetadata());
    }

    @Test
    @DisplayName("Wires exactly one vector query on SearchOptions with the configured kNN count and vector field")
    void searchWiresSingleVectorQueryWithConfiguredKnn() throws SearchServiceException {
        final SearchOptions options = captureSearchOptions("query");

        // v12 removes VectorSearchOptions; queries hang directly off SearchOptions.
        final List<VectorQuery> vectorQueries = options.getVectorQueries();
        assertEquals(1, vectorQueries.size());
        assertEquals(50, vectorQueries.get(0).getKNearestNeighbors());
        assertEquals(IndexConstants.CHUNK_VECTOR, vectorQueries.get(0).getFields());
    }

    @Test
    @DisplayName("Keyword leg is unchanged: Lucene-escaped text on SearchOptions, QueryType.FULL, top = configured pool")
    void searchCarriesEscapedQueryTextAndFullQueryType() throws SearchServiceException {
        // v12 folds the query text into SearchOptions; the escaping must still be applied to it.
        final SearchOptions options = captureSearchOptions("Crown (v) O'Brien");

        assertThat(options.getSearchText(), is("Crown \\(v\\) O'Brien"));
        assertThat(options.getQueryType(), is(QueryType.FULL));
        assertEquals(50, options.getTop());
    }

    @Test
    @DisplayName("Select list is unchanged and still selects the chunk vector")
    void searchSelectsTheConfiguredColumns() throws SearchServiceException {
        final SearchOptions options = captureSearchOptions("query");

        assertThat(options.getSelect(), is(List.of(service.getColumnsToRetrieve(null))));
    }

    private SearchOptions captureSearchOptions(final String userQuery) throws SearchServiceException {
        final SearchPagedIterable mockPagedIterable = mock(SearchPagedIterable.class);
        when(mockPagedIterable.iterator()).thenReturn(Collections.<SearchResult>emptyList().iterator());
        final ArgumentCaptor<SearchOptions> optionsCaptor = ArgumentCaptor.forClass(SearchOptions.class);
        when(mockSearchClient.search(optionsCaptor.capture())).thenReturn(mockPagedIterable);

        service.search(null, userQuery, List.of(1.0f, 2.0f), List.of(new KeyValuePair("k", "v")));

        return optionsCaptor.getValue();
    }

    private static SearchResult searchResult(final Map<String, Object> document) {
        return new SearchResult().setAdditionalProperties(document);
    }

    @Test
    @DisplayName("generateFilterExpression returns isActive != false for empty filters")
    void generateFilterExpressionReturnsIsActiveNeFalseForEmptyFilters() {
        assertThat(service.generateFilterExpression(null, Collections.emptyList()), is("(not customMetadata/any(m: m/key eq 'is_active') or customMetadata/any(m: m/key eq 'is_active' and m/value ne 'false'))"));
        assertThat(service.generateFilterExpression(null, null), is("(not customMetadata/any(m: m/key eq 'is_active') or customMetadata/any(m: m/key eq 'is_active' and m/value ne 'false'))"));
    }

    @Test
    @DisplayName("generateFilterExpression returns correct filter string for single filter")
    void generateFilterExpressionReturnsCorrectStringForSingleFilter() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("foo", "bar"));
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'foo' and m/value eq 'bar')"));
    }

    @Test
    @DisplayName("generateFilterExpression joins multiple filters with and")
    void generateFilterExpressionJoinsMultipleFiltersWithAnd() throws Exception {

        final List<KeyValuePair> filters = Arrays.asList(
                new KeyValuePair("foo", "bar"),
                new KeyValuePair("baz", "qux")
        );
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains(" and "));
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'foo' and m/value eq 'bar')"));
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'baz' and m/value eq 'qux')"));
    }

    private static final String IS_ACTIVE_FILTER_LITERAL =
            "(not customMetadata/any(m: m/key eq 'is_active') or customMetadata/any(m: m/key eq 'is_active' and m/value ne 'false'))";

    @Test
    @DisplayName("generateFilterExpression escapes single quote in value")
    void generateFilterExpression_escapesSingleQuoteInValue() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("caseName", "Crown v O'Brien"));
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'caseName' and m/value eq 'Crown v O''Brien')"));
    }

    @Test
    @DisplayName("generateFilterExpression escapes single quote in key")
    void generateFilterExpression_escapesSingleQuoteInKey() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("o'key", "v"));
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'o''key' and m/value eq 'v')"));
    }

    @Test
    @DisplayName("generateFilterExpression neutralises OData injection in value")
    void generateFilterExpression_neutralisesODataInjectionPayloadInValue() {
        // Pre-fix: this payload would close the literal early and inject `or 'a' eq 'a'`,
        // making the any() predicate vacuously true for every chunk -> filter bypass.
        // Post-fix: doubled quotes demote the entire payload to an inert string literal.
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "x' or 'a' eq 'a"));
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'k' and m/value eq 'x'' or ''a'' eq ''a')"));
    }

    @Test
    @DisplayName("generateFilterExpression neutralises is_active bypass payload")
    void generateFilterExpression_neutralisesIsActiveBypassPayload() {
        // Pre-fix: an injected top-level `or` could lift the user clause outside the and-joined
        // IS_ACTIVE_FILTER guard (since `and` binds tighter than `or`), surfacing soft-deleted
        // documents. Post-fix: the entire payload is contained inside one string literal.
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "x') or (true"));
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains("m/value eq 'x'') or (true'"));
        // The security-trimming guard must remain the outermost and-joined trailing clause.
        assertTrue(result.endsWith(" and " + IS_ACTIVE_FILTER_LITERAL),
                "IS_ACTIVE_FILTER must remain the trailing and-joined clause; was: " + result);
    }

    @Test
    @DisplayName("generateFilterExpression handles empty string value")
    void generateFilterExpression_handlesEmptyStringValue() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", ""));
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'k' and m/value eq '')"));
    }

    @Test
    @DisplayName("generateFilterExpression preserves is_active trailer with escaped pair")
    void generateFilterExpression_preservesIsActiveTrailerWithEscapedPair() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("caseName", "O'Brien"));
        final String result = service.generateFilterExpression(null, filters);
        assertThat(result, is(
                "customMetadata/any(m: m/key eq 'caseName' and m/value eq 'O''Brien')"
                        + " and "
                        + IS_ACTIVE_FILTER_LITERAL));
    }

    @Test
    @DisplayName("generateFilterExpression applies escape to every pair when mixed")
    void generateFilterExpression_appliesEscapeToEveryPairWhenMixed() {
        final List<KeyValuePair> filters = Arrays.asList(
                new KeyValuePair("plain", "value"),
                new KeyValuePair("caseName", "O'Brien")
        );
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'plain' and m/value eq 'value')"));
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'caseName' and m/value eq 'O''Brien')"));
        assertTrue(result.contains(" and "));
        assertTrue(result.endsWith(" and " + IS_ACTIVE_FILTER_LITERAL));
    }

    // Unit test for getColumnsToRetrieve
    @Test
    @DisplayName("getColumnsToRetrieve always includes the chunk vector column")
    void getColumnsToRetrieveAlwaysIncludesVector() {
        // The search service is agnostic of the dedup/MMR toggles; the vector is always fetched and
        // the downstream services decide whether to use it.
        final List<String> columns = List.of(service.getColumnsToRetrieve(null));
        assertTrue(columns.contains(IndexConstants.CHUNK_VECTOR));
        assertTrue(columns.contains(IndexConstants.CHUNK));
        assertTrue(columns.contains(IndexConstants.ID));
    }

    @Test
    @DisplayName("generateFilterExpression prepends the client-scoping clause when a client id is present")
    void generateFilterExpression_prependsClientScopingClauseWhenClientIdPresent() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("caseName", "O'Brien"));
        final String result = service.generateFilterExpression("client-a", filters);
        assertThat(result, is(
                "clientId eq 'client-a'"
                        + " and "
                        + "customMetadata/any(m: m/key eq 'caseName' and m/value eq 'O''Brien')"
                        + " and "
                        + IS_ACTIVE_FILTER_LITERAL));
    }

    @Test
    @DisplayName("generateFilterExpression prepends the client-scoping clause with no metadata filters")
    void generateFilterExpression_prependsClientScopingClauseWithNoMetadataFilters() {
        final String result = service.generateFilterExpression("client-a", Collections.emptyList());
        assertThat(result, is("clientId eq 'client-a' and " + IS_ACTIVE_FILTER_LITERAL));
    }

    @Test
    @DisplayName("generateFilterExpression escapes single quote in the client id")
    void generateFilterExpression_escapesSingleQuoteInClientId() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("foo", "bar"));
        final String result = service.generateFilterExpression("a'b", filters);
        assertTrue(result.startsWith("clientId eq 'a''b' and "),
                "client-scoping clause must lead with the escaped client id; was: " + result);
    }

    @Test
    @DisplayName("generateFilterExpression is unchanged for a null client id")
    void generateFilterExpression_isUnchangedForNullClientId() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("caseName", "O'Brien"));
        assertThat(service.generateFilterExpression(null, filters), is(
                "customMetadata/any(m: m/key eq 'caseName' and m/value eq 'O''Brien')"
                        + " and "
                        + IS_ACTIVE_FILTER_LITERAL));
    }

    @Test
    @DisplayName("generateFilterExpression is unchanged for an empty client id")
    void generateFilterExpression_isUnchangedForEmptyClientId() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("caseName", "O'Brien"));
        assertThat(service.generateFilterExpression("", filters), is(
                "customMetadata/any(m: m/key eq 'caseName' and m/value eq 'O''Brien')"
                        + " and "
                        + IS_ACTIVE_FILTER_LITERAL));
    }

    @Test
    @DisplayName("getColumnsToRetrieve includes the client id column when a client scope is supplied")
    void getColumnsToRetrieveIncludesClientIdColumn() {
        final List<String> columns = List.of(service.getColumnsToRetrieve("client-a"));
        assertTrue(columns.contains(IndexConstants.CLIENT_ID));
    }

    @Test
    @DisplayName("getColumnsToRetrieve omits the client id column when no client scope is supplied — the live index may not define the field")
    void getColumnsToRetrieveOmitsClientIdColumn_whenNoClientScope() {
        final List<String> columns = List.of(service.getColumnsToRetrieve(null));
        assertFalse(columns.contains(IndexConstants.CLIENT_ID));
    }

    @Test
    @DisplayName("search applies the client-scoping clause to the query filter")
    void search_appliesClientScopingClauseToQueryFilter() throws SearchServiceException {
        final List<Float> vector = Arrays.asList(1.0f, 2.0f);
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "v"));
        final SearchPagedIterable mockPagedIterable = mock(SearchPagedIterable.class);
        when(mockPagedIterable.iterator()).thenReturn(Collections.<SearchResult>emptyList().iterator());

        final ArgumentCaptor<SearchOptions> optionsCaptor = ArgumentCaptor.forClass(SearchOptions.class);
        when(mockSearchClient.search(optionsCaptor.capture())).thenReturn(mockPagedIterable);

        service.search("client-a", "query", vector, filters);

        assertTrue(optionsCaptor.getValue().getFilter().startsWith("clientId eq 'client-a' and "),
                "search filter must lead with the client-scoping clause; was: " + optionsCaptor.getValue().getFilter());
    }

}

