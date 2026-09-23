package uk.gov.moj.cp.orchestrator.util;

import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.azure.json.JsonProviders;
import com.azure.search.documents.SearchServiceVersion;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.SearchIndexClientBuilder;
import com.azure.search.documents.indexes.models.SearchIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates and deletes per-run AI Search indexes from the schema resources shipped in the shared
 * artefacts jar, so each test run works against its own randomly-named, initially-empty index
 * instead of a pre-existing shared one. The schema JSON carries the production index name, so it
 * is rewritten to the per-run name before parsing (a {@code SearchIndex} is immutable once built).
 */
public class IndexUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexUtil.class);

    public static void createIndexFromSchema(final String endpoint, final String indexName, final String schemaResource) {
        LOGGER.info("Connecting to '{}' and creating index '{}' from schema '{}'...", endpoint, indexName, schemaResource);
        try {
            final SearchIndex index = SearchIndex.fromJson(
                    JsonProviders.createReader(loadSchemaWithName(schemaResource, indexName)));
            getIndexClient(endpoint).createOrUpdateIndex(index);
            LOGGER.info("Index '{}' created successfully.", indexName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create index " + indexName, e);
        }
    }

    public static void deleteIndex(final String endpoint, final String indexName) {
        LOGGER.info("Connecting to '{}' and deleting index '{}'...", endpoint, indexName);
        try {
            getIndexClient(endpoint).deleteIndex(indexName);
            LOGGER.info("Index '{}' deleted.", indexName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete index " + indexName, e);
        }
    }

    private static SearchIndexClient getIndexClient(final String endpoint) {
        return new SearchIndexClientBuilder()
                .endpoint(endpoint)
                .credential(getCredentialInstance())
                // createOrUpdateIndex round-trips the schema JSON, so the api-version is pinned to the one
                // the production indexes are built under — otherwise a newer default could introduce
                // api-version-only defaults into the per-run test index.
                .serviceVersion(SearchServiceVersion.V2025_09_01)
                .buildClient();
    }

    private static String loadSchemaWithName(final String schemaResource, final String indexName) throws Exception {
        try (InputStream in = IndexUtil.class.getResourceAsStream(schemaResource)) {
            if (in == null) {
                throw new IllegalStateException("Schema resource not found on classpath: " + schemaResource);
            }
            final ObjectMapper mapper = new ObjectMapper();
            final ObjectNode root = (ObjectNode) mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            root.put("name", indexName);
            return mapper.writeValueAsString(root);
        }
    }
}
