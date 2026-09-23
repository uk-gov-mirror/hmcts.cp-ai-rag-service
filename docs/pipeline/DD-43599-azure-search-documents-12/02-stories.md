# [DD-43599] Migrate azure-search-documents 11.8.1 to 12.0.2

## User story
As a **platform engineer maintaining cp-ai-rag-service**,
I want **the pinned `azure-search-documents` dependency repinned to 12.0.2 with all call sites rewritten onto the v12 API**,
so that **the reactor tracks the current Azure SDK BOM with zero behavioural, contract or index-schema change**.

## Background
The 11.8.1 pin blocks Renovate dashboard issue #1. v12 removes `SearchDocument`, `SearchResult.getDocument(...)`,
`uploadDocuments`/`mergeDocuments`. Scope covers `AISearchClientFactory`, `DocumentStorageService`,
`AzureAISearchService`, `ai-document-migration-tool`, and `ai-service-orchestration-test/IndexUtil` — one reactor,
one managed version, single PR (per orchestration decisions).

## Acceptance criteria
- [ ] AC-001: Given the parent POM, when `mvn dependency:tree` is run across all modules, then `azure-search-documents:12.0.2` resolves as the single version with no omitted-for-conflict entries.
- [ ] AC-002: Given a retrieval query, when hybrid search executes, then it still issues one `chunkVector` kNN query plus the Lucene-escaped keyword query at `QueryType.FULL`, unchanged.
- [ ] AC-003: Given filter generation, when unit tests run, then OData filters (apostrophe escaping, `customMetadata/any(m: ...)`, `is_active` trailer, client-scoped and unscoped `client_id` variants) are byte-for-byte identical to 11.8.1.
- [ ] AC-004: Given a supersede operation, when chunks are merged, then only `is_active` flips to `false` in `customMetadata`, with id + metadata only sent, and all other fields untouched.
- [ ] AC-005: Given chunk upload, when a chunk has a null or wrong-size vector, then it is skipped with a WARN, not uploaded, not fatal; an empty batch remains a no-op WARN.
- [ ] AC-006: Given an index created under 11.8.1, when the integration suite runs (RBAC enabled) end to end (ingest → retrieve → answer), then it passes with no schema/wire drift, a pre-migration document is still retrievable, and a post-migration superseded document is excluded.
- [ ] AC-007: Given `ai-document-migration-tool` and `ai-service-orchestration-test/IndexUtil`, when built against v12, then both compile and their existing behaviour (copy/upload/index create-teardown) is unchanged.
- [ ] AC-008: Given the merge, then the stale 11.8.1 pin rationale comment is replaced with one reflecting "ahead of BOM", and Renovate dashboard issue #1 closes.

## NFR links
- No accessibility/UI surface. NFR-5 (single resolved SDK version, no split classpath) and NFR-6 (no material latency/token regression) apply — verified by AC-001 and the integration suite respectively.

## Out of scope for this story
- Index re-creation, reindexing, or schema migration.
- Retrieval tuning (kNN/top/MMR/dedup thresholds and env vars).
- Adopting v12-only capabilities (semantic ranker, new query types).
- Any change to prompt, citation guard, idempotency or scoring paths.
- OpenAPI contract change — none required; `api-cp-ai-rag` untouched.

## Definition of done
- [ ] Code reviewed and approved
- [ ] `mvn clean verify` green across the full reactor
- [ ] Unit tests assert filter strings, vector-dimension skips and upload payloads (not just that the SDK was called)
- [ ] Integration suite (`./ai-service-orchestration-test/run-integration-test.sh`) green with RBAC enabled
- [ ] SonarQube analysis clean on the PR
- [ ] Jira ticket updated with test evidence

## Notes / open questions
- Soft-delete merge semantics mapping (`mergeDocuments` + untyped `SearchDocument` → v12 merge shape) is a design-stage detail, not a separate story.
