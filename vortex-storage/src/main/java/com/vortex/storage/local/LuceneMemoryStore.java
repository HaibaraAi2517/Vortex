package com.vortex.storage.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.model.CheckpointMetadata;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;
import com.vortex.common.serialization.JsonMapperFactory;
import com.vortex.common.serialization.KryoSerializer;
import com.vortex.storage.api.*;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/**
 * Single-process durable memory: BM25, CJK bigrams, HNSW vectors and archives in one index.
 * Readers only open committed snapshots. A successful mutation includes fsync and publication;
 * a failed commit poisons this instance so subsequent writes cannot accidentally commit it.
 */
@Component
@ConditionalOnProperty(name = "vortex.storage.backend", havingValue = "local")
public class LuceneMemoryStore implements L2WarmStore, L3ColdStore, IndexedMemoryStore, FactMemoryStore, AutoCloseable {
    private static final String FACT_PREFIX = "fact-";
    private static final String SCHEMA_VERSION = "2";
    private final ObjectMapper mapper = JsonMapperFactory.create();
    private final KryoSerializer kryo = new KryoSerializer();
    private final CJKAnalyzer analyzer = new CJKAnalyzer();
    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private final int dimension;
    private volatile boolean failed;

    @Autowired
    public LuceneMemoryStore(
            @Value("${vortex.storage.local.path:./data/vortex/index}") String path,
            @Value("${vortex.storage.l2.embedding-dim:512}") int dimension) throws IOException {
        this(Path.of(path), dimension);
    }

    public LuceneMemoryStore(Path path, int dimension) throws IOException {
        if (dimension < 1 || dimension > 1024) throw new IllegalArgumentException("Vector dimension must be 1..1024");
        this.dimension = dimension;
        directory = FSDirectory.open(path.toAbsolutePath().normalize());
        IndexWriter openedWriter = null;
        try {
            boolean upgrade = false;
            if (DirectoryReader.indexExists(directory)) {
                try (var reader = DirectoryReader.open(directory)) {
                    Map<String, String> metadata = reader.getIndexCommit().getUserData();
                    upgrade = "1".equals(metadata.get("vortex.schema"));
                    if ((!SCHEMA_VERSION.equals(metadata.get("vortex.schema")) && !upgrade)
                            || !Integer.toString(dimension).equals(metadata.get("vortex.dimension"))) {
                        throw new IllegalStateException("Local index schema or embedding dimension mismatch; use its original configuration or a new data directory");
                    }
                }
            }
            openedWriter = new IndexWriter(directory, new IndexWriterConfig(analyzer));
            if (upgrade) upgradeFactCatalog(openedWriter);
            openedWriter.setLiveCommitData(Map.of("vortex.schema", SCHEMA_VERSION,
                    "vortex.dimension", Integer.toString(dimension)).entrySet());
            openedWriter.commit();
            writer = openedWriter;
            searchers = new SearcherManager(directory, new SearcherFactory());
        } catch (IOException | RuntimeException ex) {
            if (openedWriter != null) openedWriter.rollback();
            directory.close();
            analyzer.close();
            throw ex;
        }
    }

    /** Upgrade only current fact metadata; keep fragments, history and revisions intact. */
    private void upgradeFactCatalog(IndexWriter target) throws IOException {
        try (var reader = DirectoryReader.open(directory)) {
            var searcher = new IndexSearcher(reader);
            Query query = new TermQuery(new Term("kind", "fact"));
            ScoreDoc after = null;
            while (true) {
                ScoreDoc[] page = searcher.searchAfter(after, query, 256).scoreDocs;
                if (page.length == 0) break;
                for (ScoreDoc hit : page) {
                    MemoryFact fact = decode(searcher.storedFields().document(hit.doc), MemoryFact.class);
                    String identity = factIdentity(fact.namespace(), fact.key());
                    target.updateDocument(id("fact:" + identity), factDocument(fact, identity, false));
                }
                after = page[page.length - 1];
            }
        }
    }

    @Override
    public synchronized void upsert(MemoryFragment fragment) {
        Objects.requireNonNull(fragment.getId(), "fragment id");
        // Managed revisions are immutable. Reinforcement, eviction and DLQ replay must never
        // resurrect a superseded, expired or erased fact (including after process restart).
        if (fragment.getId().startsWith(FACT_PREFIX) || document("deleted:" + fragment.getId()).isPresent()) return;
        Document doc = fragmentDocument(fragment, null, null);
        byte[] payload = binary(doc, "payload");
        Optional<Document> existing = document("fragment:" + fragment.getId());
        if (existing.isPresent() && Arrays.equals(payload, binary(existing.get(), "payload"))) return;
        mutate(() -> writer.updateDocument(id("fragment:" + fragment.getId()), doc));
    }

    @Override
    public void archiveFragment(MemoryFragment fragment) { upsert(fragment); }

    @Override
    public Optional<MemoryFragment> get(String id) {
        return document("fragment:" + id).map(doc -> decode(doc, MemoryFragment.class));
    }

    @Override
    public Optional<MemoryFragment> retrieveFragment(String id) { return get(id); }

    @Override
    public boolean isRecallable(String fragmentId) {
        if (!fragmentId.startsWith(FACT_PREFIX)) return document("deleted:" + fragmentId).isEmpty();
        return withSearcher(searcher -> searcher.count(new BooleanQuery.Builder()
                .add(new TermQuery(id("fragment:" + fragmentId)), BooleanClause.Occur.FILTER)
                .add(unexpired(), BooleanClause.Occur.FILTER).build()) > 0);
    }

    @Override
    public List<MemoryFragment> search(float[] embedding, String namespace, int topK) {
        return searchVectors(embedding, namespace, List.of(), topK);
    }

    @Override
    public List<MemoryFragment> searchVectors(float[] embedding, String namespace, List<String> tags, int topK) {
        if (topK <= 0) return List.of();
        Query query = new KnnFloatVectorQuery("vector", normalizedVector(embedding), topK, fragmentFilter(namespace, tags));
        return documents(query, topK, null).stream().map(doc -> decode(doc, MemoryFragment.class)).toList();
    }

    @Override
    public List<KeywordHit> searchKeywords(String text, String namespace, List<String> tags, int limit) {
        if (limit <= 0) return List.of();
        Set<String> terms = LexicalAnalysis.terms(text);
        if (terms.isEmpty()) return List.of();
        var lexical = new BooleanQuery.Builder();
        // Bound query work, never the indexed corpus. No user-controlled query syntax.
        terms.stream().limit(256).forEach(term -> lexical.add(new TermQuery(new Term("text", term)), BooleanClause.Occur.SHOULD));
        Query query = new BooleanQuery.Builder()
                .add(fragmentFilter(namespace, tags), BooleanClause.Occur.FILTER)
                .add(lexical.build(), BooleanClause.Occur.MUST).build();
        return withSearcher(searcher -> {
            List<KeywordHit> hits = new ArrayList<>();
            for (ScoreDoc hit : searcher.search(query, limit).scoreDocs) {
                hits.add(new KeywordHit(decode(searcher.storedFields().document(hit.doc), MemoryFragment.class), hit.score));
            }
            return hits;
        });
    }

    @Override
    public List<MemoryFragment> listByNamespace(String namespace, int limit) {
        if (limit <= 0) return List.of();
        return documents(fragmentFilter(namespace, List.of()), limit, null).stream()
                .map(doc -> decode(doc, MemoryFragment.class)).toList();
    }

    @Override
    public synchronized void delete(String fragmentId) {
        Optional<Document> doc = document("fragment:" + fragmentId);
        if (doc.isPresent() && doc.get().get("current") != null) {
            MemoryFact current = document("fact:" + doc.get().get("current"))
                    .map(value -> decode(value, MemoryFact.class)).orElseThrow();
            deleteFact(current.namespace(), current.key(), current.revision());
        } else {
            Document tombstone = base("deleted:" + fragmentId, "deleted", new byte[0]);
            mutate(() -> {
                writer.deleteDocuments(id("fragment:" + fragmentId));
                writer.updateDocument(id("deleted:" + fragmentId), tombstone);
            });
        }
    }

    @Override
    public void deleteFragment(String id) { delete(id); }

    @Override
    public int vectorDimension() { return dimension; }

    @Override
    public boolean synchronousDurability() { return true; }

    @Override
    public synchronized MemoryFact putFact(String key, MemoryFragment fragment, long expectedRevision, Instant expiresAt) {
        String namespace = fragment.getNamespace();
        validateIdentity(namespace, key);
        if (fragment.getContent() == null || fragment.getContent().isBlank()) throw new IllegalArgumentException("Fact content is required");
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) throw new IllegalArgumentException("expiresAt must be in the future");
        MemoryFact previous = checkRevision(namespace, key, expectedRevision);
        long revision = Math.incrementExact(expectedRevision);
        Instant now = Instant.now();
        String identity = factIdentity(namespace, key);
        fragment.setId(FACT_PREFIX + UUID.randomUUID());
        MemoryFact fact = new MemoryFact(namespace, key, revision, fragment.getId(), fragment.getContent(),
                fragment.getTags() == null ? List.of() : List.copyOf(fragment.getTags()), MemoryFact.State.ACTIVE,
                previous == null ? now : previous.createdAt(), now, expiresAt);
        List<Document> current = List.of(factDocument(fact, identity, false), fragmentDocument(fragment, identity, expiresAt));
        Document history = factDocument(fact, identity, true);
        mutate(() -> {
            writer.updateDocuments(new Term("current", identity), current);
            writer.addDocument(history);
        });
        return fact;
    }

    @Override
    public Optional<MemoryFact> getFact(String namespace, String key) {
        validateIdentity(namespace, key);
        return document("fact:" + factIdentity(namespace, key)).map(doc -> decode(doc, MemoryFact.class));
    }

    @Override
    public Predicate<String> excludeFactKeys(String namespace, Set<String> keys) {
        var excluded = new TermInSetQuery("current", keys.stream()
                .map(key -> new BytesRef(factIdentity(namespace, key))).toList());
        return fragmentId -> !fragmentId.startsWith(FACT_PREFIX) || withSearcher(searcher -> searcher.count(
                new BooleanQuery.Builder().add(new TermQuery(id("fragment:" + fragmentId)), BooleanClause.Occur.FILTER)
                        .add(new TermQuery(new Term("namespace", namespace)), BooleanClause.Occur.FILTER)
                        .add(unexpired(), BooleanClause.Occur.FILTER)
                        .add(excluded, BooleanClause.Occur.MUST_NOT).build()) > 0);
    }

    @Override
    public List<MemoryFact> listFacts(String namespace, String afterKey, int limit, boolean includeInactive) {
        validateIdentity(namespace, afterKey == null || afterKey.isEmpty() ? "_" : afterKey);
        if (limit < 1 || limit > 101) throw new IllegalArgumentException("Invalid facts page size");
        var query = new BooleanQuery.Builder()
                .add(new TermQuery(new Term("kind", "fact")), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term("namespace", namespace)), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term("state", MemoryFact.State.DELETED.name())), BooleanClause.Occur.MUST_NOT);
        if (!includeInactive) {
            query.add(new TermQuery(new Term("state", MemoryFact.State.ACTIVE.name())), BooleanClause.Occur.FILTER)
                    .add(unexpired(), BooleanClause.Occur.FILTER);
        }
        if (afterKey != null && !afterKey.isEmpty()) {
            query.add(TermRangeQuery.newStringRange("fact-key", afterKey, null, false, false), BooleanClause.Occur.FILTER);
        }
        return documents(query.build(), limit, new Sort(new SortField("fact-key-sort", SortField.Type.STRING)))
                .stream().map(doc -> decode(doc, MemoryFact.class)).toList();
    }

    @Override
    public List<MemoryFact> factHistory(String namespace, String key, long beforeRevision, int limit) {
        validateIdentity(namespace, key);
        if (limit < 1 || limit > 100 || beforeRevision < 1) throw new IllegalArgumentException("Invalid history page");
        Query query = new BooleanQuery.Builder()
                .add(new TermQuery(new Term("kind", "history")), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term("owner", factIdentity(namespace, key))), BooleanClause.Occur.FILTER)
                .add(LongPoint.newRangeQuery("revision", 0, beforeRevision - 1), BooleanClause.Occur.FILTER).build();
        return documents(query, limit, new Sort(new SortField("revision-sort", SortField.Type.LONG, true)))
                .stream().map(doc -> decode(doc, MemoryFact.class)).toList();
    }

    @Override
    public synchronized MemoryFact retractFact(String namespace, String key, long expectedRevision) {
        return removeFact(namespace, key, expectedRevision, false);
    }

    @Override
    public synchronized MemoryFact deleteFact(String namespace, String key, long expectedRevision) {
        return removeFact(namespace, key, expectedRevision, true);
    }

    private MemoryFact removeFact(String namespace, String key, long expectedRevision, boolean erase) {
        validateIdentity(namespace, key);
        MemoryFact previous = checkRevision(namespace, key, expectedRevision);
        if (previous == null) throw new IllegalArgumentException("Fact does not exist");
        MemoryFact fact = new MemoryFact(namespace, key, Math.incrementExact(expectedRevision), null, null, List.of(),
                erase ? MemoryFact.State.DELETED : MemoryFact.State.RETRACTED,
                previous.createdAt(), Instant.now(), null);
        String identity = factIdentity(namespace, key);
        Document current = factDocument(fact, identity, false);
        Document history = factDocument(fact, identity, true);
        mutate(() -> {
            if (erase) writer.deleteDocuments(new Term("owner", identity));
            writer.updateDocuments(new Term("current", identity), List.of(current));
            writer.addDocument(history);
        });
        return fact;
    }

    private MemoryFact checkRevision(String namespace, String key, long expected) {
        if (expected < 0) throw new IllegalArgumentException("expectedRevision must be non-negative");
        MemoryFact current = getFact(namespace, key).orElse(null);
        long actual = current == null ? 0 : current.revision();
        if (actual != expected) throw new MemoryRevisionConflictException(expected, actual);
        return current;
    }

    @Override
    public String saveCheckpoint(TaskState state) {
        return saveCheckpointWithMetadata(state, CheckpointMetadata.builder().taskId(state.getTaskId())
                .sequenceNumber(state.getWalSequenceNumber()).build()).getCheckpointId();
    }

    @Override
    public CheckpointMetadata saveCheckpointWithMetadata(TaskState state, CheckpointMetadata meta) {
        if (!Objects.equals(state.getTaskId(), meta.getTaskId())) throw new IllegalArgumentException("Checkpoint task mismatch");
        state.setLatestCheckpointId(meta.getCheckpointId());
        state.setLastCheckpointAt(Instant.now());
        return saveCheckpointBytesWithMetadata(kryo.serializeCompressed(state), meta);
    }

    @Override
    public synchronized CheckpointMetadata saveCheckpointBytesWithMetadata(byte[] data, CheckpointMetadata meta) {
        String key = checkpointKey(meta.getTaskId() + "/" + meta.getCheckpointId());
        meta.setL3Key(key);
        meta.setSizeBytes(data.length);
        meta.setCompressed(true);
        meta.setCompressionAlgorithm("gzip");
        Document doc = base("checkpoint:" + key, "checkpoint", encode(meta));
        doc.add(new StringField("task", meta.getTaskId(), Field.Store.YES));
        doc.add(new StoredField("bytes", data));
        mutate(() -> writer.updateDocument(id("checkpoint:" + key), doc));
        return meta;
    }

    @Override
    public Optional<TaskState> loadCheckpoint(String reference) {
        byte[] bytes = getBytes(checkpointKey(reference));
        if (bytes == null) return Optional.empty();
        try {
            return Optional.of(kryo.deserializeCompressed(bytes, TaskState.class));
        } catch (KryoSerializer.VersionMismatchException ex) {
            throw new CheckpointStoreException(CheckpointStoreException.FailureType.VERSION_MISMATCH, "Checkpoint version mismatch", ex);
        } catch (RuntimeException ex) {
            throw new CheckpointStoreException(CheckpointStoreException.FailureType.PAYLOAD_INVALID, "Invalid local checkpoint", ex);
        }
    }

    @Override
    public synchronized void deleteCheckpoint(String reference) {
        mutate(() -> writer.deleteDocuments(id("checkpoint:" + checkpointKey(reference))));
    }

    @Override
    public List<CheckpointMetadata> listCheckpointMetadata(String taskId) {
        Query query = new BooleanQuery.Builder().add(new TermQuery(new Term("kind", "checkpoint")), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term("task", taskId)), BooleanClause.Occur.FILTER).build();
        return documents(query, Integer.MAX_VALUE, null).stream().map(doc -> decode(doc, CheckpointMetadata.class))
                .sorted(CheckpointMetadata.chronologicalOrder()).toList();
    }

    @Override
    public Set<String> listTaskIdsWithCheckpoints() {
        Set<String> ids = new HashSet<>();
        documents(new TermQuery(new Term("kind", "checkpoint")), Integer.MAX_VALUE, null).forEach(doc -> ids.add(doc.get("task")));
        return ids;
    }

    @Override
    public synchronized void putBytes(String key, byte[] data) {
        Document doc = base("object:" + key, "object", data);
        mutate(() -> writer.updateDocument(id("object:" + key), doc));
    }

    @Override
    public byte[] getBytes(String key) {
        Optional<Document> object = document("object:" + key);
        return object.map(doc -> binary(doc, "payload"))
                .orElseGet(() -> document("checkpoint:" + key).map(doc -> binary(doc, "bytes")).orElse(null));
    }

    private Document fragmentDocument(MemoryFragment fragment, String identity, Instant expiresAt) {
        Document doc = base("fragment:" + fragment.getId(), "fragment", encode(fragment));
        doc.add(new StringField("namespace", Objects.requireNonNull(fragment.getNamespace()), Field.Store.NO));
        doc.add(new TextField("text", Objects.requireNonNull(fragment.getContent()), Field.Store.NO));
        if (fragment.getTags() != null) for (String tag : fragment.getTags()) {
            doc.add(new StringField("tag", tag.strip(), Field.Store.NO));
            doc.add(new TextField("text", tag, Field.Store.NO));
        }
        float[] vector = fragment.getL2Embedding() == null ? fragment.getEmbedding() : fragment.getL2Embedding();
        if (vector != null) doc.add(new KnnFloatVectorField("vector", normalizedVector(vector), VectorSimilarityFunction.DOT_PRODUCT));
        doc.add(new LongPoint("expires", expiresAt == null ? Long.MAX_VALUE : expiresAt.toEpochMilli()));
        if (identity != null) {
            doc.add(new StringField("current", identity, Field.Store.YES));
            doc.add(new StringField("owner", identity, Field.Store.NO));
        }
        return doc;
    }

    private Document factDocument(MemoryFact fact, String identity, boolean history) {
        Document doc = base(history ? "history:" + identity + ":" + fact.revision() : "fact:" + identity,
                history ? "history" : "fact", encode(fact));
        doc.add(new StringField("owner", identity, Field.Store.NO));
        if (!history) {
            doc.add(new StringField("current", identity, Field.Store.YES));
            doc.add(new StringField("namespace", fact.namespace(), Field.Store.NO));
            doc.add(new StringField("fact-key", fact.key(), Field.Store.NO));
            doc.add(new SortedDocValuesField("fact-key-sort", new BytesRef(fact.key())));
            doc.add(new StringField("state", fact.state().name(), Field.Store.NO));
            doc.add(new LongPoint("expires", fact.expiresAt() == null ? Long.MAX_VALUE : fact.expiresAt().toEpochMilli()));
        }
        doc.add(new LongPoint("revision", fact.revision()));
        doc.add(new NumericDocValuesField("revision-sort", fact.revision()));
        return doc;
    }

    private static Document base(String id, String kind, byte[] payload) {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        doc.add(new StringField("kind", kind, Field.Store.NO));
        doc.add(new StoredField("payload", payload));
        return doc;
    }

    private static Query fragmentFilter(String namespace, List<String> tags) {
        var filter = new BooleanQuery.Builder()
                .add(new TermQuery(new Term("kind", "fragment")), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term("namespace", namespace)), BooleanClause.Occur.FILTER)
                .add(unexpired(), BooleanClause.Occur.FILTER);
        if (tags != null) for (String tag : tags) filter.add(new TermQuery(new Term("tag", tag.strip())), BooleanClause.Occur.FILTER);
        return filter.build();
    }

    private static Query unexpired() { return LongPoint.newRangeQuery("expires", System.currentTimeMillis() + 1, Long.MAX_VALUE); }
    private static Term id(String value) { return new Term("id", value); }

    private Optional<Document> document(String key) {
        return documents(new TermQuery(id(key)), 1, null).stream().findFirst();
    }

    private List<Document> documents(Query query, int limit, Sort sort) {
        return withSearcher(searcher -> {
            int bounded = Math.min(limit, Math.max(1, searcher.getIndexReader().maxDoc()));
            TopDocs hits = sort == null ? searcher.search(query, bounded) : searcher.search(query, bounded, sort);
            List<Document> docs = new ArrayList<>();
            for (ScoreDoc hit : hits.scoreDocs) docs.add(searcher.storedFields().document(hit.doc));
            return docs;
        });
    }

    private <T> T withSearcher(SearchAction<T> action) {
        if (failed) throw new IllegalStateException("Local storage failed; restart after checking disk health");
        try {
            IndexSearcher searcher = searchers.acquire();
            try { return action.run(searcher); }
            finally { searchers.release(searcher); }
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    private void mutate(WriteAction action) {
        if (failed) throw new IllegalStateException("Local storage failed; restart after checking disk health");
        try {
            action.run();
            writer.commit();
            searchers.maybeRefreshBlocking();
        } catch (IOException | RuntimeException ex) {
            failed = true;
            try { writer.rollback(); } catch (Exception rollback) { ex.addSuppressed(rollback); }
            throw new IllegalStateException("Local memory commit failed; restart after checking disk health", ex);
        }
    }

    private byte[] encode(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    private <T> T decode(Document doc, Class<T> type) {
        try { return mapper.readValue(binary(doc, "payload"), type); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    private static byte[] binary(Document doc, String field) {
        var bytes = doc.getBinaryValue(field);
        return Arrays.copyOfRange(bytes.bytes, bytes.offset, bytes.offset + bytes.length);
    }

    private float[] normalizedVector(float[] vector) {
        if (vector == null || vector.length != dimension) throw new IllegalArgumentException("Expected " + dimension + " embedding dimensions");
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Embedding must be finite");
            norm += (double) value * value;
        }
        if (norm == 0) throw new IllegalArgumentException("Embedding must not be zero");
        float[] result = vector.clone();
        double length = Math.sqrt(norm);
        for (int i = 0; i < result.length; i++) result[i] = (float) (result[i] / length);
        return result;
    }

    private static void validateIdentity(String namespace, String key) {
        if (namespace == null || namespace.isBlank() || namespace.length() > 128 || namespace.indexOf('\0') >= 0
                || key == null || key.isBlank() || key.length() > 128 || key.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("namespace and key must contain 1..128 characters without NUL");
        }
    }

    private static String factIdentity(String namespace, String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((namespace + '\0' + key).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static String checkpointKey(String reference) {
        String key = reference.startsWith("checkpoints/") ? reference : "checkpoints/" + reference;
        return key.endsWith(".kryo") ? key : key + ".kryo";
    }

    @PreDestroy
    @Override
    public synchronized void close() throws IOException {
        searchers.close();
        if (writer.isOpen()) writer.close();
        analyzer.close();
        directory.close();
    }

    @FunctionalInterface private interface SearchAction<T> { T run(IndexSearcher searcher) throws IOException; }
    @FunctionalInterface private interface WriteAction { void run() throws IOException; }
}
