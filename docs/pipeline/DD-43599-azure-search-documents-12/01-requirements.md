# DD-43599 — Requirements: migrate `azure-search-documents` 11.8.1 → 12.0.2

## Purpose
The parent `pom.xml` pins `com.azure:azure-search-documents` to `11.8.1`, overriding `azure-sdk-bom` 1.3.8
(which manages `12.0.1`). The pin comment records why: v12 is a breaking redesign — `SearchDocument`,
`SearchResult.getDocument(...)`, `uploadDocuments`/`mergeDocuments` removed — and defers the rewrite to a
separate, integration-tested change. Renovate dashboard issue #1 targets `12.0.2`. This change removes the
pin, rewrites the affected call sites onto the v12 API, and must land with zero behavioural, contract or
index-schema change. Maintenance/currency only.

## Affected code (verified by grep)
| Module | Class | v12-breaking surface |
|---|---|---|
| shared-artefacts | `AISearchClientFactory` | `SearchClient` / `SearchClientBuilder`, per-index cache |
| shared-artefacts | `StringUtil` | `escapeODataStringLiteral` / `escapeLuceneSpecialChars` (no SDK import; contract-adjacent) |
| ingestion-function | `DocumentStorageService` | `SearchDocument`, `uploadDocuments`, `mergeDocuments`, `SearchResult.getDocument`, `SearchOptions`, `SearchPagedIterable` |
| answer-retrieval-function | `AzureAISearchService` | `SearchOptions`, `VectorizedQuery`, `VectorSearchOptions`, `QueryType.FULL`, `getDocument(ChunkedEntry.class)` |
| migration-tool *(not in brief)* | `IndexCopier`, `SyncUploader`, `BufferedSenderUploader`, `SearchIndexAdmin`, `IndexMigrationTool` | `SearchIndexingBufferedSender`, `IndexDocumentsOptions`, `IndexingResult`, `SearchIndexClient` |
| orchestration-test *(not in brief)* | `IndexUtil` | `SearchIndexClientBuilder`, index create/teardown |

## Functional requirements
- **FR-1** Hybrid search unchanged: one vector query (`chunkVector`, kNN = `SEARCH_NEAREST_NEIGHBOURS_COUNT`)
  plus the Lucene-escaped keyword query at `QueryType.FULL`, returning `SEARCH_TOP_RESULTS_COUNT` candidates.
- **FR-2** OData filter generation byte-for-byte identical, including `customMetadata/any(m: ...)` clauses and
  `'` → `''` escaping via `escapeODataStringLiteral`.
- **FR-3** Soft-delete trailer preserved: every retrieval filter still ends with the `is_active` clause.
- **FR-4** Client scope preserved both ways: leading `client_id eq '...'` on retrieval, grouped
  `client_id eq '...' and (<doc clauses>)` on supersede; null/empty `clientId` yields the pre-multi-client string.
- **FR-5** Chunk upload unchanged: same fields/values, same vector-dimension guard (null or non-`VECTOR_DIMENSIONS`
  vectors skipped with a WARN, not failed), `client_id` emitted only when non-empty, empty batch a no-op WARN.
- **FR-6** Supersede path unchanged: matched chunks read, `is_active=false` appended to `customMetadata`,
  merged back as id + metadata only.
- **FR-7** Deserialisation to `ChunkedEntry` unchanged, so containment-dedup → semantic-dedup → MMR receives
  identical input; those three services need no edit.
- **FR-8** Managed-identity auth and the per-index client cache behave as now.
- **FR-9** Migration tool and integration-test index utilities compile and run on v12.

## Non-functional requirements
- **NFR-1** No OpenAPI contract change; `api-cp-ai-rag` untouched.
- **NFR-2** No index schema change — same fields, analyzers, vector profile; existing indexes read/written in
  place, no reindex.
- **NFR-3** Gate is a green integration suite (`./ai-service-orchestration-test/run-integration-test.sh`) against
  real Azure, covering ingest → retrieve → answer.
- **NFR-4** Unit coverage maintained; rewritten tests still assert filter strings, vector-dimension skips and
  upload payloads — not merely that the SDK was called.
- **NFR-5** Single resolved version across all modules; no split 11.x/12.x classpath, no `azure-core` conflict.
- **NFR-6** Retrieval latency and token usage not materially changed.

## Out of scope
- Index re-creation, reindexing, schema migration.
- Retrieval tuning: kNN / top / MMR / dedup thresholds and env vars stay as-is.
- Adopting v12-only capabilities (semantic ranker, new query types).
- Prompt, citation guard, idempotency and scoring paths.

## Acceptance criteria
- **AC-1** Pin removed; resolved `azure-search-documents` is `12.0.2` in every module (`mvn dependency:tree`
  shows one version, no omitted-for-conflict entries).
- **AC-2** `mvn clean verify` passes for all modules.
- **AC-3** Unit tests assert the same filter expressions as on 11.8.1 — apostrophe escaping, `is_active`
  trailer, client-scoped and unscoped variants.
- **AC-4** Integration suite passes end to end against an index created under 11.8.1 (no schema/wire drift).
- **AC-5** A document ingested pre-migration is still retrievable; a document superseded post-migration is excluded.
- **AC-6** Chunks with a null or wrong-size vector are still skipped, not uploaded, not fatal.
- **AC-7** Stale pin rationale comment removed/replaced; Renovate issue #1 closes on merge.

## Open questions
1. Repin explicitly to `12.0.2`, or drop the pin and inherit `12.0.1` from `azure-sdk-bom` 1.3.8? — Owner: TBD
2. Is `ai-document-migration-tool` in scope here or migrated separately? It is the heaviest user of removed
   APIs (`SearchIndexingBufferedSender`). — Owner: TBD
3. Do v12 merge semantics without `SearchDocument` require a different soft-delete approach? — Owner: TBD

## Decisions (orchestration, 23 Sep 2026)
1. **Repin explicitly to 12.0.2** — Renovate's target; azure-sdk-bom 1.3.8 manages only 12.0.1. Keep the
   dependencyManagement entry with a refreshed comment (pin now means "ahead of the BOM", not "held back").
2. **`ai-document-migration-tool` and `ai-service-orchestration-test/IndexUtil` are in scope** — one reactor,
   one managed version; they cannot stay on v11 once dependencyManagement moves. Single PR migrates all consumers.
3. **Soft-delete merge semantics** — to be answered concretely in the design stage: map `mergeDocuments` +
   untyped `SearchDocument` to the v12 merge operation shape, preserving partial-update behaviour (only
   `is_active` flipped, all other fields untouched).
