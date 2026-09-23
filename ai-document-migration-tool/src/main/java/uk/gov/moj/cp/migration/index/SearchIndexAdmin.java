package uk.gov.moj.cp.migration.index;

import static uk.gov.moj.cp.ai.client.AISearchClientFactory.SERVICE_VERSION;

import static java.lang.String.format;
import static uk.gov.moj.cp.ai.index.IndexConstants.ID;

import uk.gov.moj.cp.ai.client.config.ClientConfiguration;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.util.CredentialUtil;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import com.azure.json.JsonProviders;
import com.azure.search.documents.SearchIndexingBufferedSender;
import com.azure.search.documents.SearchIndexingBufferedSenderBuilder;
import com.azure.search.documents.SearchServiceVersion;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.SearchIndexClientBuilder;
import com.azure.search.documents.indexes.models.SearchIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Index management and client construction for the migration: the index-level client, the buffered sender,
 * creating the target index from a v2 schema, verifying source/target counts, and emitting the
 * alias-cutover command. All managed-identity authenticated via the shared credential.
 */
final class SearchIndexAdmin {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchIndexAdmin.class);

    private static final int MAX_RETRIES_PER_ACTION = 3;
    private static final Duration AUTO_FLUSH_INTERVAL = Duration.ofSeconds(10);

    private SearchIndexAdmin() {
    }

    /**
     * REST api-version pinned to the one the source/target indexes were built and queried under, so the
     * schema round-trip through {@code createOrUpdateIndex} cannot pick up newer api-version defaults.
     */

    static SearchIndexClient indexClient(final String endpoint) {
        return new SearchIndexClientBuilder()
                .endpoint(endpoint)
                .credential(CredentialUtil.getCredentialInstance())
                .serviceVersion(SERVICE_VERSION)
                .retryOptions(ClientConfiguration.getRetryOptions())
                .httpClient(ClientConfiguration.createNettyClient())
                .buildClient();
    }

    static SearchIndexingBufferedSender<ChunkedEntry> bufferedSender(final String endpoint,
                                                                     final String indexName,
                                                                     final int initialBatchActionCount,
                                                                     final AtomicLong succeeded,
                                                                     final AtomicLong failed) {
        return new SearchIndexingBufferedSenderBuilder<ChunkedEntry>()
                .endpoint(endpoint)
                .indexName(indexName)
                .credential(CredentialUtil.getCredentialInstance())
                .serviceVersion(SERVICE_VERSION)
                .retryOptions(ClientConfiguration.getRetryOptions())
                .httpClient(ClientConfiguration.createNettyClient())
                // The sender keys documents off the raw action body, not the typed entry: the uploader
                // hands it explicit IndexActions so no document serializer is involved at all.
                .documentKeyRetriever(document -> (String) document.get(ID))
                .initialBatchActionCount(initialBatchActionCount)
                .maxRetriesPerAction(MAX_RETRIES_PER_ACTION)
                .autoFlushInterval(AUTO_FLUSH_INTERVAL)
                .onActionSucceeded(options -> succeeded.incrementAndGet())
                .onActionError(options -> {
                    failed.incrementAndGet();
                    LOGGER.error("Indexing action failed for document", options.getThrowable());
                })
                .buildSender();
    }

    static void createTargetIndex(final SearchIndexClient indexClient,
                                  final String targetIndex,
                                  final String schemaResource) throws Exception {
        // SearchIndex is immutable (no setName), and the index name comes from the JSON, so override
        // the "name" field to the desired physical target before parsing. Every other property
        // (vectorSearch, semantic, similarity, fields...) is preserved verbatim from the schema file.
        final SearchIndex index = SearchIndex.fromJson(
                JsonProviders.createReader(loadSchemaWithName(schemaResource, targetIndex)));
        indexClient.createOrUpdateIndex(index);
        LOGGER.info("Created/updated target index '{}' from schema '{}'.", targetIndex, schemaResource);
    }

    static String loadSchemaWithName(final String schemaResource, final String targetIndex) throws Exception {
        try (InputStream in = SearchIndexAdmin.class.getResourceAsStream(schemaResource)) {
            if (in == null) {
                throw new IllegalStateException("Schema resource not found on classpath: " + schemaResource);
            }
            final ObjectMapper mapper = new ObjectMapper();
            final ObjectNode root = (ObjectNode) mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            root.put("name", targetIndex);
            return mapper.writeValueAsString(root);
        }
    }

    static void verifyCounts(final SearchIndexClient indexClient,
                             final String sourceIndex,
                             final String targetIndex,
                             final long succeeded) {
        final long src = indexClient.getSearchClient(sourceIndex).getDocumentCount();
        final long dst = indexClient.getSearchClient(targetIndex).getDocumentCount();
        LOGGER.info("Verification — source={}, target={}, indexed={}.", src, dst, succeeded);
        if (src != dst) {
            throw new IllegalStateException(format(
                    "Document count mismatch: source=%d target=%d. A chunk may have been skipped by the "
                            + "vector validity check. Investigate before cutover.", src, dst));
        }
    }

    /**
     * Emits the alias create/repoint command for the cutover. The pinned azure-search-documents Java SDK
     * has no index-alias API, but the data-plane REST API does — so this is run as an ops step
     * ({@code az rest} below, or the portal). Idempotent {@code PUT}; allow ~10s to propagate before
     * relying on the alias for queries. {@code --resource https://search.azure.com} acquires an AAD token
     * for the search data plane via the same managed-identity / {@code az login} credential the tool uses.
     */
    static void logCutoverCommand(final String endpoint, final String aliasName, final String targetIndex) {
        final String trimmed = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        LOGGER.info("Copy verified. Final cutover step — point the alias at '{}' (the Java SDK has no alias API; "
                + "run this REST call):\n\n"
                + "az rest --method put \\\n"
                + "  --uri \"{}/aliases/{}?api-version=2024-07-01\" \\\n"
                + "  --resource \"https://search.azure.com\" \\\n"
                + "  --headers \"Content-Type=application/json\" \\\n"
                + "  --body '{\"name\":\"{}\",\"indexes\":[\"{}\"]}'\n\n"
                + "Then set AZURE_SEARCH_SERVICE_INDEX_NAME to '{}' in each environment's function settings.",
                targetIndex, trimmed, aliasName, aliasName, targetIndex, aliasName);
    }
}
