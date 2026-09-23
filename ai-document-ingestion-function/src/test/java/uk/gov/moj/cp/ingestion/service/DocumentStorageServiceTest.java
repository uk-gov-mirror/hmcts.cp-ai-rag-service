package uk.gov.moj.cp.ingestion.service;

import static dev.langchain4j.internal.Utils.randomUUID;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.index.IndexConstants.CUSTOM_METADATA;
import static uk.gov.moj.cp.ai.index.IndexConstants.FALSE_VALUE;
import static uk.gov.moj.cp.ai.index.IndexConstants.ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.IS_ACTIVE;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.models.IndexAction;
import com.azure.search.documents.models.IndexActionType;
import com.azure.search.documents.models.IndexDocumentsBatch;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchPagedIterable;
import com.azure.search.documents.models.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DocumentStorageServiceTest {

    private DocumentStorageService documentStorageService;

    @BeforeEach
    void setUp() {
        documentStorageService = new DocumentStorageService("https://test-search.endpoint", "test-index");
    }

    @Test
    @DisplayName("Handle Null Chunks List")
    void shouldHandleNullChunksList() throws Exception {
        // when & then
        assertThrows(NullPointerException.class,
                () -> documentStorageService.uploadChunks(null));
    }

    @Test
    @DisplayName("Handle Empty Chunks List")
    void shouldHandleEmptyChunksList() throws Exception {
        // given
        List<ChunkedEntry> emptyChunks = Collections.emptyList();

        // when & then
        assertDoesNotThrow(() -> documentStorageService.uploadChunks(emptyChunks));
    }

    @Test
    @DisplayName("Service Constructor Works")
    void shouldCreateServiceWithValidParameters() {
        // when
        DocumentStorageService service = new DocumentStorageService("https://test-endpoint", "test-index");

        // then
        assertNotNull(service);
    }

    @Test
    @DisplayName("Service Constructor with Null Or Empty Endpoint should Throw Exception")
    void shouldThrowExceptionWithNullAdminKey() {
        assertThrows(IllegalArgumentException.class, () ->
            new DocumentStorageService("", "test-index"));

        assertThrows(IllegalArgumentException.class, () ->
                new DocumentStorageService(null, "test-index"));
    }

    @Test
    @DisplayName("Service Constructor with Null Or Empty Index Name Should Throw Exception")
    void shouldThrowExceptionWithEmptyAdminKey() {
        assertThrows(IllegalArgumentException.class, () ->
            new DocumentStorageService("https://test-endpoint", ""));
        assertThrows(IllegalArgumentException.class, () ->
                new DocumentStorageService("https://test-endpoint", null));
    }

    /**
     * v12 replaces {@code mergeDocuments(List&lt;SearchDocument&gt;)} with a single
     * {@code indexDocuments(IndexDocumentsBatch)} call. The wire body is only equivalent to the v11
     * merge if every action is typed {@link IndexActionType#MERGE} and carries <em>only</em> the key
     * plus the metadata field — a merge action naming any other field would overwrite it service-side.
     */
    @Test
    void shouldMarkDocumentsInactive_andSendMergeBatch() {
        // given
        final SearchClient searchClient = mock(SearchClient.class);
        when(searchClient.getIndexName()).thenReturn("test-index");
        final DocumentStorageService documentStorageService = new DocumentStorageService(searchClient);

        final List<String> ids = List.of("doc1", "doc2");
        // Immutable inputs: the implementation must copy the retrieved metadata before appending.
        final SearchResult result1 = searchResult(Map.of(
                ID, "doc1",
                CUSTOM_METADATA, List.of(Map.of("key", "case_id", "value", randomUUID()))));
        final SearchResult result2 = searchResult(Map.of(
                ID, "doc2",
                CUSTOM_METADATA, List.of()));

        final SearchPagedIterable iterable = mock(SearchPagedIterable.class);
        when(iterable.iterator()).thenReturn(List.of(result1, result2).iterator());

        when(searchClient.search(any(SearchOptions.class))).thenReturn(iterable);

        // when
        documentStorageService.markDocumentsInActive(null, ids);

        // then
        final List<IndexAction> actions = captureBatch(searchClient).getActions();
        assertThat(actions.size(), is(2));

        for (final IndexAction action : actions) {
            assertThat(action.getActionType(), is(IndexActionType.MERGE));
            // Partial-update semantics (FR-6/AC-004): only the key and the metadata collection.
            assertThat(action.getAdditionalProperties().keySet(), is(Set.of(ID, CUSTOM_METADATA)));
            assertIsActiveFalseAppended(action);
        }
        assertThat(actions.get(0).getAdditionalProperties().get(ID), is("doc1"));
        assertThat(actions.get(1).getAdditionalProperties().get(ID), is("doc2"));
    }

    @Test
    void shouldNotIndexDocuments_whenNoResultsFound() {
        // given
        final SearchClient searchClient = mock(SearchClient.class);
        when(searchClient.getIndexName()).thenReturn("test-index");
        final DocumentStorageService documentStorageService = new DocumentStorageService(searchClient);

        final List<String> ids = List.of("doc1");

        final SearchPagedIterable iterable = mock(SearchPagedIterable.class);
        when(iterable.iterator()).thenReturn(Collections.emptyIterator());

        when(searchClient.search(any(SearchOptions.class))).thenReturn(iterable);

        // when
        documentStorageService.markDocumentsInActive(null, ids);

        // then
        verify(searchClient, never()).indexDocuments(any(IndexDocumentsBatch.class));
    }

    @Test
    void shouldCreateMetadata_whenMissing() {
        // given
        final SearchClient searchClient = mock(SearchClient.class);
        when(searchClient.getIndexName()).thenReturn("test-index");
        final DocumentStorageService documentStorageService = new DocumentStorageService(searchClient);

        final List<String> ids = List.of("doc1");
        // no metadata on the retrieved document
        final SearchResult result = searchResult(Map.of(ID, "doc1"));

        final SearchPagedIterable iterable = mock(SearchPagedIterable.class);
        when(iterable.iterator()).thenReturn(List.of(result).iterator());
        when(searchClient.search(any(SearchOptions.class))).thenReturn(iterable);

        // when
        documentStorageService.markDocumentsInActive(null, ids);

        // then
        final IndexAction action = captureBatch(searchClient).getActions().get(0);
        assertThat(action.getActionType(), is(IndexActionType.MERGE));
        assertThat(action.getAdditionalProperties().keySet(), is(Set.of(ID, CUSTOM_METADATA)));
        assertIsActiveFalseAppended(action);
    }

    @Test
    @DisplayName("Supersede read sends searchText '*' and selects only id and customMetadata")
    void shouldReadSupersedeCandidatesWithWildcardTextAndMinimalSelect() {
        // given
        final SearchClient searchClient = mock(SearchClient.class);
        when(searchClient.getIndexName()).thenReturn("test-index");
        final DocumentStorageService documentStorageService = new DocumentStorageService(searchClient);

        final SearchPagedIterable iterable = mock(SearchPagedIterable.class);
        when(iterable.iterator()).thenReturn(Collections.emptyIterator());

        final ArgumentCaptor<SearchOptions> optionsCaptor = ArgumentCaptor.forClass(SearchOptions.class);
        when(searchClient.search(optionsCaptor.capture())).thenReturn(iterable);

        // when
        documentStorageService.markDocumentsInActive(null, List.of("doc1"));

        // then — v12 folds the query text into SearchOptions; omitting it changes the query semantics.
        assertThat(optionsCaptor.getValue().getSearchText(), is("*"));
        // $select must be two discrete field names, not one comma-joined string.
        assertThat(optionsCaptor.getValue().getSelect(), is(List.of(ID, CUSTOM_METADATA)));
    }

    /**
     * v12 surfaces the raw document body on the result rather than via {@code getDocument(Class)}.
     */
    private static SearchResult searchResult(final Map<String, Object> document) {
        return new SearchResult().setAdditionalProperties(document);
    }

    private static IndexDocumentsBatch captureBatch(final SearchClient searchClient) {
        final ArgumentCaptor<IndexDocumentsBatch> captor = ArgumentCaptor.forClass(IndexDocumentsBatch.class);
        verify(searchClient).indexDocuments(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static void assertIsActiveFalseAppended(final IndexAction action) {
        final List<Map<String, String>> metadata =
                (List<Map<String, String>>) action.getAdditionalProperties().get(CUSTOM_METADATA);
        final Map<String, String> isActive = metadata.stream()
                .filter(mt -> IS_ACTIVE.equals(mt.get("key")))
                .findFirst()
                .orElseThrow();
        assertThat(isActive.get("key"), is(IS_ACTIVE));
        assertThat(isActive.get("value"), is(FALSE_VALUE));
    }

    @Test
    void shouldBuildCorrectFilter() {
        // given
        final SearchClient searchClient = mock(SearchClient.class);
        when(searchClient.getIndexName()).thenReturn("test-index");
        final DocumentStorageService documentStorageService = new DocumentStorageService(searchClient);

        final List<String> ids = List.of("doc1", "doc2");

        final SearchPagedIterable iterable = mock(SearchPagedIterable.class);
        when(iterable.iterator()).thenReturn(Collections.emptyIterator());

        final ArgumentCaptor<SearchOptions> optionsCaptor = ArgumentCaptor.forClass(SearchOptions.class);
        when(searchClient.search(optionsCaptor.capture())).thenReturn(iterable);

        // when
        documentStorageService.markDocumentsInActive(null, ids);

        // then
        final String filter = optionsCaptor.getValue().getFilter();

        assertThat(filter.contains("customMetadata/any(m: m/key eq 'documentId' and m/value eq 'doc1'"), is(true));
        assertThat(filter.contains("customMetadata/any(m: m/key eq 'documentId' and m/value eq 'doc2'"), is(true));
        assertThat(filter.contains("or"), is(true));
    }

    @Test
    @DisplayName("Supersede filter is scoped by the client id when provided")
    void shouldScopeSupersedeFilterByClientId() {
        // given
        final SearchClient searchClient = mock(SearchClient.class);
        when(searchClient.getIndexName()).thenReturn("test-index");
        final DocumentStorageService documentStorageService = new DocumentStorageService(searchClient);

        final List<String> ids = List.of("doc1", "doc2");

        final SearchPagedIterable iterable = mock(SearchPagedIterable.class);
        when(iterable.iterator()).thenReturn(Collections.emptyIterator());

        final ArgumentCaptor<SearchOptions> optionsCaptor = ArgumentCaptor.forClass(SearchOptions.class);
        when(searchClient.search(optionsCaptor.capture())).thenReturn(iterable);

        // when
        documentStorageService.markDocumentsInActive("client-a", ids);

        // then
        final String filter = optionsCaptor.getValue().getFilter();
        assertThat(filter.startsWith("clientId eq 'client-a' and "), is(true));
    }

    @Test
    @DisplayName("Supersede filter is unchanged when no client id is provided")
    void shouldNotScopeSupersedeFilterWhenClientIdNull() {
        // given
        final SearchClient searchClient = mock(SearchClient.class);
        when(searchClient.getIndexName()).thenReturn("test-index");
        final DocumentStorageService documentStorageService = new DocumentStorageService(searchClient);

        final List<String> ids = List.of("doc1", "doc2");

        final SearchPagedIterable iterable = mock(SearchPagedIterable.class);
        when(iterable.iterator()).thenReturn(Collections.emptyIterator());

        final ArgumentCaptor<SearchOptions> optionsCaptor = ArgumentCaptor.forClass(SearchOptions.class);
        when(searchClient.search(optionsCaptor.capture())).thenReturn(iterable);

        // when
        documentStorageService.markDocumentsInActive(null, ids);

        // then
        final String filter = optionsCaptor.getValue().getFilter();
        assertThat(filter.startsWith("customMetadata/any(m: m/key eq 'documentId'"), is(true));
        assertThat(filter.contains("clientId eq"), is(false));
    }
}
