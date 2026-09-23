package uk.gov.moj.cp.migration.index;

import static java.lang.String.format;
import static uk.gov.moj.cp.ai.index.IndexConstants.ID;
import static uk.gov.moj.cp.ai.util.StringUtil.escapeODataStringLiteral;

import uk.gov.moj.cp.ai.index.SearchFieldMapper;
import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchPagedIterable;
import com.azure.search.documents.models.SearchPagedResponse;
import com.azure.search.documents.models.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The parallel copy engine. Reads the source via keyset pagination — a single full-range scan with one
 * worker, or 16 concurrent {@link Shard}s on a fixed pool — and streams valid chunks into the shared
 * buffered sender. Tracks aggregate progress for the ETA, honours an optional global record cap, and
 * runs a no-progress watchdog so a hung request can never wedge the run indefinitely.
 */
final class IndexCopier {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexCopier.class);

    /** Embedding dimension; chunks whose stored vector doesn't match are skipped (surfaced via count check). */
    static final int VECTOR_DIMENSIONS = 3072;

    /** Watchdog backstop: abort if the aggregate processed count doesn't advance for this long — a true
     *  hang (e.g. a half-open socket parking a flush) can never wedge the run. Sized above plausible
     *  all-workers-throttling back-off to avoid false aborts. */
    private static final Duration STALL_TIMEOUT = Duration.ofMinutes(3);

    /** How long to block on a worker before re-checking the stall watchdog. */
    private static final Duration WATCHDOG_POLL = Duration.ofSeconds(15);

    private final SearchClient source;
    private final DocumentUploader uploader;
    private final int pageSize;
    private final int workers;
    private final long maxRecords; // <= 0 means copy everything; otherwise a global cap (sample copy)
    /** Fixed clientId stamped onto every copied chunk before upload, or {@code null} to copy verbatim. */
    private final String clientIdOverride;

    // Shared across worker threads for aggregate progress / ETA.
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private long total;          // ETA denominator (min of source size and maxRecords)
    private long baseProcessed;  // docs already copied before this run (resume), for the ETA
    private long startNanos;     // set before workers start -> happens-before via task submission

    /** Convenience constructor for a verbatim copy — no clientId is stamped onto the copies. */
    IndexCopier(final SearchClient source,
                final DocumentUploader uploader,
                final int pageSize,
                final int workers,
                final long maxRecords) {
        this(source, uploader, pageSize, workers, maxRecords, null);
    }

    IndexCopier(final SearchClient source,
                final DocumentUploader uploader,
                final int pageSize,
                final int workers,
                final long maxRecords,
                final String clientIdOverride) {
        this.source = source;
        this.uploader = uploader;
        this.pageSize = pageSize;
        this.workers = Math.max(1, workers);
        this.maxRecords = maxRecords;
        this.clientIdOverride = clientIdOverride;
    }

    private boolean limited() {
        return maxRecords > 0;
    }

    /**
     * Copies documents from the source to the target. With one worker this is a single sequential keyset
     * scan (resumable via {@code startAfterId}); with more, the key space is split into 16 shards read
     * concurrently. All workers stream into the shared buffered sender.
     *
     * @return the number of documents submitted (valid vectors only, capped at {@code maxRecords}).
     */
    long copyAllDocuments(final String startAfterId) {
        final long sourceCount = source.getDocumentCount();
        total = limited() ? Math.min(sourceCount, maxRecords) : sourceCount;
        baseProcessed = countProcessedBefore(startAfterId);
        startNanos = System.nanoTime();
        submitted.set(0);
        skipped.set(0);

        final List<Shard> shards = Shard.plan(workers, startAfterId);
        final int poolSize = Math.min(this.workers, shards.size());
        if (limited()) {
            LOGGER.info("Copying up to {} record(s) (SAMPLE of {}) using {} worker(s) over {} shard(s).",
                    maxRecords, sourceCount, poolSize, shards.size());
        } else {
            LOGGER.info("Copying {} document(s) using {} worker(s) over {} shard(s).",
                    sourceCount, poolSize, shards.size());
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(poolSize)) {
            final List<Future<?>> futures = new ArrayList<>(shards.size());
            for (final Shard shard : shards) {
                futures.add(pool.submit(() -> copyShard(shard)));
            }
            awaitWithWatchdog(futures);
        }

        LOGGER.info("Read complete — {} submitted ({} skipped) across {} shard(s).",
                submitted.get(), skipped.get(), shards.size());
        return submitted.get();
    }

    /** Reads one shard end-to-end via keyset pagination, streaming valid chunks to the shared sender. */
    private void copyShard(final Shard shard) {
        final String label = shard.label();
        String lastId = shard.startCursor();

        while (!capReachedGlobally()) {
            final List<ChunkedEntry> page = readPage(shard, lastId);
            if (page.isEmpty()) {
                break;
            }
            final boolean capHit = uploadPage(page, label);
            lastId = page.get(page.size() - 1).id(); // advance by the raw page's last id (filtered rows kept in order)
            logProgress(label, lastId);

            if (capHit) {
                LOGGER.info("[shard {}] record cap of {} reached; stopping.", label, maxRecords);
                break;
            }
            if (page.size() < pageSize) {
                break; // last (partial) page for this shard
            }
        }
    }

    /** True once the global record cap has been reached (by this or another shard). */
    private boolean capReachedGlobally() {
        return limited() && submitted.get() >= maxRecords;
    }

    /**
     * Filters a page to valid-vector chunks, records skips, claims budget, and streams the claimed slice to
     * the shared sender. Returns {@code true} if the global cap was hit mid-page (i.e. the page was trimmed).
     */
    private boolean uploadPage(final List<ChunkedEntry> page, final String label) {
        final List<ChunkedEntry> valid = page.stream().filter(IndexCopier::hasValidVector).toList();
        recordSkips(page.size() - (long) valid.size(), label);
        if (valid.isEmpty()) {
            return false;
        }
        final long take = claim(valid.size()); // bumps submitted; trims to the remaining cap when limited
        if (take > 0) {
            final List<ChunkedEntry> claimed = take == valid.size() ? valid : valid.subList(0, (int) take);
            // The uploader is either async (buffered sender) or sync (per-page, memory-bounded); either way a
            // transient per-doc error is counted, not fatal.
            uploader.upload(stampForUpload(claimed));
        }
        return take < valid.size();
    }

    /**
     * Maps the claimed slice to the entries handed to the uploader. When a fixed clientId is configured each
     * entry is copied with that value set; with no override configured the entries are copied verbatim.
     */
    private List<ChunkedEntry> stampForUpload(final List<ChunkedEntry> entries) {
        if (clientIdOverride == null) {
            return entries; // no override — copy verbatim
        }
        return entries.stream().map(this::withClientId).toList();
    }

    /** Copies an entry with the configured clientId set and every other field preserved. */
    private ChunkedEntry withClientId(final ChunkedEntry source) {
        return source.toBuilder()
                .clientId(clientIdOverride)
                .build();
    }

    private void recordSkips(final long pageSkipped, final String label) {
        if (pageSkipped > 0) {
            skipped.addAndGet(pageSkipped);
            LOGGER.warn("[shard {}] skipped {} chunk(s) with a missing/wrong-size vector (expected {} dims).",
                    label, pageSkipped, VECTOR_DIMENSIONS);
        }
    }

    private void logProgress(final String label, final String lastId) {
        final long processedThisRun = submitted.get() + skipped.get();
        final long processedOverall = baseProcessed + processedThisRun;
        final long remaining = Math.max(0, total - processedOverall);
        LOGGER.info("[shard {}] Read to cursor id={}; {} submitted, {} skipped so far. "
                        + "Est. time remaining: {} ({}/{} processed).",
                label, lastId, submitted.get(), skipped.get(),
                formatEta(processedThisRun, remaining, System.nanoTime() - startNanos),
                processedOverall, total);
    }

    /**
     * Atomically reserves up to {@code want} slots against the run, returning how many may be uploaded.
     * Unlimited runs always grant {@code want}; a limited run grants only the remaining budget so the global
     * cap is honoured exactly without overshoot across concurrent shards. Bumps the {@code submitted} counter.
     */
    private long claim(final int want) {
        if (!limited()) {
            submitted.addAndGet(want);
            return want;
        }
        while (true) {
            final long current = submitted.get();
            if (current >= maxRecords) {
                return 0;
            }
            final long take = Math.min(want, maxRecords - current);
            if (submitted.compareAndSet(current, current + take)) {
                return take;
            }
        }
    }

    /**
     * Waits for all shard workers, surfacing the first failure, and aborts if the aggregate processed count
     * doesn't advance for {@link #STALL_TIMEOUT}. On any abnormal exit the remaining workers are cancelled
     * so the pool terminates promptly.
     */
    private void awaitWithWatchdog(final List<Future<?>> futures) {
        long lastProcessed = -1;
        long lastProgressNanos = System.nanoTime();
        try {
            for (final Future<?> future : futures) {
                while (true) {
                    try {
                        future.get(WATCHDOG_POLL.toMillis(), TimeUnit.MILLISECONDS);
                        break; // this shard finished successfully
                    } catch (final TimeoutException waiting) {
                        final long processed = submitted.get() + skipped.get();
                        if (processed != lastProcessed) {
                            lastProcessed = processed;
                            lastProgressNanos = System.nanoTime();
                        } else if (System.nanoTime() - lastProgressNanos > STALL_TIMEOUT.toNanos()) {
                            throw new IllegalStateException(format(
                                    "Migration stalled: no progress for %d min (processed %d/%d). Likely a hung "
                                            + "request — re-run to recover (uploads are idempotent).",
                                    STALL_TIMEOUT.toMinutes(), processed, total));
                        }
                    }
                }
            }
        } catch (final InterruptedException e) {
            cancelAll(futures);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Migration interrupted", e);
        } catch (final ExecutionException e) {
            cancelAll(futures);
            throw new IllegalStateException("A migration worker failed", e.getCause());
        } catch (final RuntimeException e) {
            cancelAll(futures); // stall (or any unexpected) — stop the rest before surfacing
            throw e;
        }
    }

    private static void cancelAll(final List<Future<?>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    /**
     * Reads one keyset page for {@code shard}, ordered by {@code id} ascending, starting after
     * {@code lastId}. No {@code setSelect} is used so every retrievable field is pulled, including
     * {@code chunkIndex} which the retrieval query path drops.
     */
    List<ChunkedEntry> readPage(final Shard shard, final String lastId) {
        final SearchOptions options = new SearchOptions()
                .setSearchText("*")
                .setTop(pageSize)
                .setOrderBy(ID + " asc"); // requires id sortable
        final String filter = shard.pageFilter(lastId);
        if (filter != null) {
            options.setFilter(filter); // requires id filterable
        }

        final SearchPagedIterable results = source.search(options);
        final List<ChunkedEntry> page = new ArrayList<>(pageSize);
        for (final SearchResult result : results) {
            page.add(SearchFieldMapper.toChunkedEntry(result.getAdditionalProperties()));
        }
        return page;
    }

    private static boolean hasValidVector(final ChunkedEntry entry) {
        return entry.chunkVector() != null && entry.chunkVector().size() == VECTOR_DIMENSIONS;
    }

    /**
     * Count of source documents already copied (id ≤ cursor) when resuming a single-worker run, so the ETA
     * "processed"/"remaining" are correct mid-stream. Returns 0 on a fresh run or any multi-worker run.
     */
    private long countProcessedBefore(final String startAfterId) {
        if (workers > 1 || startAfterId == null || startAfterId.isEmpty()) {
            return 0;
        }
        final SearchOptions options = new SearchOptions()
                .setSearchText("*")
                .setFilter(format("%s le '%s'", ID, escapeODataStringLiteral(startAfterId)))
                .setTop(0)
                .setIncludeTotalCount(true);
        // The total count now lives on the page rather than the iterable, so read the first page directly.
        final Iterator<SearchPagedResponse> pages = source.search(options).iterableByPage().iterator();
        if (!pages.hasNext()) {
            return 0;
        }
        final Long count = pages.next().getCount();
        return count == null ? 0 : count;
    }

    /**
     * Formats an ETA as {@code HH:MM:SS} from this run's throughput: {@code processedThisRun} over
     * {@code elapsedNanos} gives a per-record rate, projected across the {@code remaining} records (hours
     * are not capped at 24). Returns "unknown" before any progress is measurable.
     */
    static String formatEta(final long processedThisRun, final long remaining, final long elapsedNanos) {
        if (processedThisRun <= 0 || elapsedNanos <= 0) {
            return "unknown";
        }
        final long etaNanos = (long) ((double) elapsedNanos / processedThisRun * remaining);
        final Duration eta = Duration.ofNanos(etaNanos);
        return format("%02d:%02d:%02d", eta.toHours(), eta.toMinutesPart(), eta.toSecondsPart());
    }
}
