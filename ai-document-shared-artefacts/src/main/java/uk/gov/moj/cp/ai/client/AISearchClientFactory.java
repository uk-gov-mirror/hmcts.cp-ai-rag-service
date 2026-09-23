package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.client.config.ClientConfiguration.createNettyClient;
import static uk.gov.moj.cp.ai.client.config.ClientConfiguration.getRetryOptions;
import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import java.util.concurrent.ConcurrentHashMap;

import com.azure.core.credential.TokenCredential;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.SearchServiceVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AISearchClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(AISearchClientFactory.class);

    /**
     * REST api-version pinned rather than left on the SDK default. The SDK's default moved on with the
     * 11 -> 12 upgrade, so pinning keeps the wire contract identical to the one the live indexes were
     * built and queried under; bumping it is a separate, revertible change.
     */
    private static final SearchServiceVersion SERVICE_VERSION = SearchServiceVersion.V2025_09_01;

    private static final ConcurrentHashMap<String, SearchClient> AI_SEARCH_CLIENT_CACHE = new ConcurrentHashMap<>();
    private static final TokenCredential SHARED_CREDENTIAL = getCredentialInstance();

    private AISearchClientFactory() {
    }

    public static SearchClient getInstance(final String endpoint, final String indexName) {

        validateNullOrEmpty(endpoint, "Endpoint value must be set.");
        validateNullOrEmpty(indexName, "Index name value must be set.");

        final String cacheKey = endpoint + ":" + indexName;

        return AI_SEARCH_CLIENT_CACHE.computeIfAbsent(
                cacheKey,
                key -> {
                    LOGGER.info("Creating new AI Search client for: {}", key);

                    return new SearchClientBuilder()
                            .endpoint(endpoint)
                            .indexName(indexName)
                            .credential(SHARED_CREDENTIAL)
                            .serviceVersion(SERVICE_VERSION)
                            .retryOptions(getRetryOptions())
                            .httpClient(createNettyClient())
                            .buildClient();
                }
        );

    }
}
