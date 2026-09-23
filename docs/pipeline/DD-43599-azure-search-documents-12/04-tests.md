# DD-43599 — A-TDD unit test layer (test code only; no prod, no pom)

Written against the **v12 target API** from `03-design.md`. Expected red until implementation lands.

| File | Change |
|---|---|
| `ai-document-ingestion-function/.../DocumentStorageServiceTest.java` | Adapted. Merge path now captures `IndexDocumentsBatch` off `indexDocuments` (was `mergeDocuments(List<SearchDocument>)`): asserts every action is `IndexActionType.MERGE` and its key set is **exactly** `{id, customMetadata}` (design §2 / risk "merge sending more fields"). `SearchResult`s are built real via `setAdditionalProperties` with **immutable** maps, forcing the defensive copy. Filter assertions kept verbatim, rewired to one-arg `search(SearchOptions)`. New test pins `searchText == "*"` and `getSelect() == [id, customMetadata]` (two varargs, not a comma-joined string). |
| `ai-document-ingestion-function/.../DocumentStorageServiceUploadPayloadTest.java` | **New.** The design §3 silent-corruption trap: asserts the upload action's `customMetadata` value is `List<Map<String,String>>` and `everyItem(instanceOf(Map.class))` — a `KeyValuePair` record there compiles and indexes `"KeyValuePair[key=…]"` strings. Also pins `chunkVector` staying `List<Float>`, `pageNumber`/`chunkIndex` staying `Integer`, `IndexActionType.UPLOAD`, and the exact field key set (with/without `clientId`). Plus the vector-dimension guard (FR-5/AC-006): null and wrong-size vectors are **skipped with a WARN, not fatal** — no `indexDocuments` call at all when every chunk is invalid, and only the valid chunk survives a mixed batch. |
| `ai-document-ingestion-function/.../DocumentStorageServiceClientIdentityTest.java` | Minimal: capture helper now reads `IndexDocumentsBatch → getActions().get(0).getAdditionalProperties()`. Assertions unchanged. |
| `ai-document-answer-retrieval-function/.../AzureAISearchServiceTest.java` | Adapted + extended. All 16 existing filter-string assertions (apostrophe escaping, OData injection, `is_active` trailer, client-scope clause, byte-identical null/empty `clientId`) kept **verbatim**. Result mapping now goes through a real `SearchResult.getAdditionalProperties()`; new test round-trips a known vector to pin `Double → List<Float>` narrowing plus every field incl. `customMetadata → KeyValuePair` (risk "numeric coercion"). New tests pin kNN wiring (`setVectorQueries` with exactly one query, `getKNearestNeighbors() == 50`, `fields == chunkVector`), the Lucene-escaped query text now on `SearchOptions.setSearchText`, `QueryType.FULL`, `top`, and the unchanged `$select`. |
| `ai-document-shared-artefacts/.../SearchFieldMapperTest.java` | **New — defines the contract** for the shared mapper of design §6.2. Required signatures: `static Map<String,Object> toSearchDocument(ChunkedEntry)` and `static ChunkedEntry toChunkedEntry(Map<String,Object>)` in `uk.gov.moj.cp.ai.index`. Pins both hazards at source (record→map on write, Double→Float on read), tolerance of a partial `$select` projection, and a full round-trip. |

**Verification run:** the three ingestion/retrieval files compile clean under `javac` against
`azure-search-documents-12.0.0` (signatures confirmed by `javap`: `IndexAction`, `IndexDocumentsBatch`,
`SearchResult.getAdditionalProperties`, `SearchClient.search(SearchOptions)`/`indexDocuments`,
`VectorQuery.getKNearestNeighbors`, `models.SearchPagedIterable`). They will **fail at runtime** until
`DocumentStorageService`/`AzureAISearchService` are migrated — that is the intended red.
`SearchFieldMapperTest` does not compile until `SearchFieldMapper` exists.

**Not touched (deliberate):** integration tests (already cover AC-004/005/006 end to end);
`ai-document-migration-tool` tests — still on v11 APIs (`bufferedSender`, `uploadDocumentsWithResponse`,
`getTotalCount`), to be fixed alongside design §6.5 implementation, not speculatively.
