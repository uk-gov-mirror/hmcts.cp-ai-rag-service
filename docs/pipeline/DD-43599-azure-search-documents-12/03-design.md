# DD-43599 — Design: `azure-search-documents` 11.8.1 → 12.0.2

> **Outcome note (post-review):** the implementation ultimately inherits the BOM-managed **12.0.1**
> rather than pinning 12.0.2 — the two differ only by dependency-floor bumps neutralised by
> azure-sdk-bom, so every API claim below (verified against the 12.0.2 jar) holds identically.

## Summary
v12 is a code-generation rewrite: the untyped `SearchDocument` map type and the typed
`uploadDocuments`/`mergeDocuments`/`getDocument(Class)` convenience layer are gone, replaced by an
`IndexAction` + `IndexDocumentsBatch` model and raw `Map<String,Object>` document bodies. Query text
and vector queries fold into `SearchOptions`. No index schema change, no reindex; all mapping below
verified by decompiling `azure-search-documents-12.0.2.jar` (Maven Central), not from docs.

## 1. v11 → v12 API mapping

| Site | v11 | v12.0.2 |
|---|---|---|
| all | `com.azure.search.documents.SearchDocument` | **removed** — use `java.util.Map<String,Object>` |
| all | `com.azure.search.documents.util.SearchPagedIterable` | `com.azure.search.documents.models.SearchPagedIterable` (package moved) |
| all | `searchClient.search(text, opts, Context.NONE)` | `searchClient.search(opts)` — text via `opts.setSearchText(text)`; 2-arg overload takes `com.azure.core.http.rest.RequestOptions`, not `Context` |
| all | `SearchResult.getDocument(T.class)` | **removed** — `SearchResult.getAdditionalProperties()` → `Map<String,Object>`; map to `ChunkedEntry` with Jackson `ObjectMapper.convertValue(map, ChunkedEntry.class)` (record is already `@JsonProperty`-annotated) |
| `AISearchClientFactory` | `SearchClientBuilder` endpoint/indexName/credential/retryOptions/httpClient/buildClient | **unchanged** (all five methods identical); add `.serviceVersion(...)` — see §4 |
| `DocumentStorageService.uploadChunks` | `searchClient.uploadDocuments(List<SearchDocument>)` | `searchClient.indexDocuments(new IndexDocumentsBatch(actions))` where each action is `new IndexAction().setActionType(IndexActionType.UPLOAD).setAdditionalProperties(fieldMap)` |
| `DocumentStorageService.markDocumentsInActive` | `searchClient.mergeDocuments(List<SearchDocument>)` | same batch call with `IndexActionType.MERGE` — see §2 |
| `DocumentStorageService.getSearchResults` | `new SearchOptions().setFilter(f).setSelect("id, customMetadata")` | same, but add `.setSearchText("*")`; split `setSelect(ID, CUSTOM_METADATA)` into two varargs |
| `AzureAISearchService` | `new VectorSearchOptions().setQueries(List.of(q))` + `opts.setVectorSearchOptions(...)` | **`VectorSearchOptions` removed** — `opts.setVectorQueries(VectorQuery...)` directly |
| `AzureAISearchService` | `VectorizedQuery.setKNearestNeighborsCount(n)` | `setKNearestNeighbors(n)` (rename); ctor `VectorizedQuery(List<Float>)` and `.setFields(String)` unchanged |
| `AzureAISearchService` | `QueryType.FULL`, `setFilter`, `setSelect(String[])`, `setTop` | **unchanged** |
| `IndexCopier.readPage` | `SearchOptions.setTop/.setOrderBy/.setFilter` | unchanged (`setOrderBy(String...)` retained); add `.setSearchText("*")` |
| `IndexCopier.countProcessedBefore` | `SearchPagedIterable.getTotalCount()` | **removed** — `iterable.iterableByPage().iterator().next().getCount()` on `SearchPagedResponse` (also where `getCoverage`/`getFacets` now live) |
| `SyncUploader` | `target.uploadDocumentsWithResponse(batch, IndexDocumentsOptions, Context)` | `target.indexDocumentsWithResponse(IndexDocumentsBatch, IndexDocumentsOptions, RequestOptions)` — `IndexDocumentsOptions.setThrowOnAnyError(false)` and `IndexingResult` (`isSucceeded`/`getKey`/`getStatusCode`/`getErrorMessage`) unchanged |
| `SearchIndexAdmin.bufferedSender` | `new SearchClientBuilder()....bufferedSender(TypeReference.createInstance(T))` | **`SearchClientBuilder.bufferedSender` removed** — `new SearchIndexingBufferedSenderBuilder<ChunkedEntry>()` with endpoint/indexName/credential/retryOptions/httpClient + the same tuning methods, then `.buildSender()` |
| `SearchIndexAdmin.bufferedSender` | `.documentKeyRetriever(ChunkedEntry::id)` | signature is now `Function<Map<String,Object>,String>` → `doc -> (String) doc.get(ID)` |
| `BufferedSenderUploader` | `sender.addUploadActions(Collection<T>)`, `flush()`, `close()` | **unchanged** (also gained `addActions(Collection<IndexAction>)` — the escape hatch in §3) |
| `SearchIndexAdmin` / `IndexUtil` | `SearchIndexClientBuilder`, `createOrUpdateIndex(SearchIndex)`, `deleteIndex(String)`, `getSearchClient(name)`, `SearchClient.getDocumentCount()`, `SearchIndex.fromJson` | **all unchanged** — `IndexUtil` needs no code change |

## 2. Soft-delete merge — the exact v12 pattern
`IndexAction.toJson` (decompiled) writes `{"@search.action":"<type>", <each additionalProperties entry>}`
— byte-identical to the v11 `mergeDocuments(SearchDocument)` wire body. A merge omitting a field leaves
it untouched service-side, so partial-update semantics are preserved by sending **only** `id` +
`customMetadata` (FR-6 / AC-004):

```java
final List<IndexAction> actions = new ArrayList<>();
for (final SearchResult result : searchResults) {
    final Map<String, Object> doc = result.getAdditionalProperties();          // id + customMetadata ($select)
    final List<Map<String, Object>> metadata = doc.containsKey(CUSTOM_METADATA)
            ? new ArrayList<>((List<Map<String, Object>>) doc.get(CUSTOM_METADATA)) : new ArrayList<>();
    metadata.add(Map.of("key", IS_ACTIVE, "value", FALSE_VALUE));
    actions.add(new IndexAction()
            .setActionType(IndexActionType.MERGE)
            .setAdditionalProperties(Map.of(ID, doc.get(ID), CUSTOM_METADATA, metadata)));
}
if (!actions.isEmpty()) { searchClient.indexDocuments(new IndexDocumentsBatch(actions)); }
```

## 3. Serialization hazard (must not be missed)
`setAdditionalProperties` values are written with `azure-json`'s `writeUntyped`, whose documented
fallback for an unrecognised POJO is `writeString(String.valueOf(value))`. Consequences:
- **`uploadChunks` must convert `List<KeyValuePair>` → `List<Map<String,String>>`** before putting it in
  the action. Passing the record compiles and indexes `"KeyValuePair[key=..., value=...]"` strings into a
  `Collection(Edm.ComplexType)` field — a runtime indexing failure, not a compile error. `List<Float>`
  (chunkVector), String, Integer, Map are all handled natively.
- **Buffered sender:** `buildSender()` falls back to `JsonSerializerProviders.createInstance(true)`. v12's
  POM no longer drags in `azure-core-serializer-json-jackson` (11.8.1 did), so the default silently drops
  from the Jackson serializer (honours `@JsonProperty`) to `DefaultJsonSerializer` (does not). Fix by
  converting `ChunkedEntry` → `Map` in `BufferedSenderUploader` and calling `sender.addActions(...)` with
  explicit `IndexAction`s — preferred, it removes the serializer dependency entirely and reuses the same
  field-map builder as `SyncUploader`/`uploadChunks`.
- `jackson-databind` itself stays on every module's classpath via other paths (checked with
  `dependency:tree`), so `ChunkedEntry`/`IndexUtil` still compile.

## 4. Index & service-version compatibility
Azure AI Search indexes are stored versionlessly; the REST api-version governs only request/response
shape, so a v12 client reads and writes an index created under 11.8.1 with **no schema change and no
reindex** (NFR-2). Default latest moves `V2025_09_01` (11.8.1) → `V2026_04_01` (12.0.2). Every feature
used here (`filter`, `select`, `top`, `orderBy`, `queryType=full`, `vectorQueries` kind `vector`,
`@search.action`) exists unchanged in both. **Pin `.serviceVersion(SearchServiceVersion.V2025_09_01)` on
`SearchClientBuilder`, `SearchIndexClientBuilder` and `SearchIndexingBufferedSenderBuilder`** for this PR
so the wire contract is provably identical to today, and bump the api-version as a separate, revertible
change. `IndexUtil`'s `createOrUpdateIndex` round-trips the schema JSON, so pinning also stops v12
introducing 2026-04-01-only defaults into the per-run test index.

## 5. Risks

| Risk | Catches it |
|---|---|
| Complex-type fields serialized as strings (§3) — silent until the service rejects the batch | Unit test asserting the exact `Map` payload of the built `IndexAction`s; integration ingest → retrieve (AC-006) |
| Untyped `Map` → `ChunkedEntry` numeric coercion: JSON numbers arrive as `Double`, narrowed to `List<Float>`; affects MMR/containment cosine inputs | Unit test round-tripping a known vector; retrieval integration test comparing returned chunk ids/order against the 11.8.1 baseline |
| Silent api-version bump changing ranking or defaults | Mitigated by pinning `V2025_09_01` (§4); integration suite is the backstop |
| Merge sending more fields than `id` + `customMetadata`, overwriting chunk content | Unit assertion on the action's key set (AC-004); AC-005 supersede check |
| Buffered-sender serializer regression in the migration tool | Removed by design (§3, explicit `IndexAction`s); `MIGRATION_UPLOAD_MODE=sync` is the fallback |

## 6. Implementation outline
1. Parent `pom.xml`: `azure-search-documents` → `12.0.2`, replace the stale pin comment ("ahead of
   `azure-sdk-bom` 1.3.8, which manages 12.0.1"). `mvn dependency:tree` → single version (AC-001).
2. Shared artefacts: add a `SearchFieldMapper` (`ChunkedEntry` ↔ `Map<String,Object>`, incl. the
   `KeyValuePair` → `Map` conversion and the `Map` → `ChunkedEntry` Jackson `convertValue`) so ingestion,
   migration tool and retrieval share one conversion. Pin `serviceVersion` in `AISearchClientFactory`.
3. `DocumentStorageService`: `uploadChunks` → `IndexAction`/`IndexDocumentsBatch` (vector-dimension skip
   and empty-batch WARN untouched); `markDocumentsInActive` → §2; `getSearchResults` → `setSearchText("*")`
   + two-vararg `setSelect`; imports to `models.SearchPagedIterable`.
4. `AzureAISearchService`: drop `VectorSearchOptions`, use `setVectorQueries(...)`, rename
   `setKNearestNeighbors`, `setSearchText(escapedUserQuery)`, `search(opts)`, map results via the shared
   mapper. `generateFilterExpression`/`getColumnsToRetrieve` untouched.
5. Migration tool: `SearchIndexAdmin.bufferedSender` → `SearchIndexingBufferedSenderBuilder` +
   `Map`-based `documentKeyRetriever`; `BufferedSenderUploader` → `addActions(IndexAction...)`;
   `SyncUploader` → `indexDocumentsWithResponse`; `IndexCopier` → `setSearchText`, `getAdditionalProperties`,
   page-level `getCount()`. `IndexUtil`: no change (verify compile only).
6. Tests: keep every existing filter-string / vector-skip assertion; add the §3 payload assertions and the
   vector round-trip assertion. Then `mvn clean verify`, then
   `./ai-service-orchestration-test/run-integration-test.sh` against a pre-existing 11.8.1 index (AC-006).
7. Smoke-build `ai-document-system-prompt-harness-eval` — a 10th reactor module that consumes the SDK
   transitively via the retrieval function; no code change expected, but it is not covered by the
   integration suite.

**Follow-ups:** ADR not warranted (dependency currency, no architectural change). Separate ticket to bump
`SearchServiceVersion` to `V2026_04_01`. Deployment out of scope (manual ADO pipeline post-merge).
