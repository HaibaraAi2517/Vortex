package com.vortex.storage.local;

import com.vortex.common.model.CheckpointMetadata;
import com.vortex.common.model.MemoryFragment;
import com.vortex.common.model.TaskState;
import com.vortex.storage.api.MemoryRevisionConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class LuceneMemoryStoreTest {
    @TempDir Path directory;

    private MemoryFragment fragment(String id, String namespace, String content) {
        return MemoryFragment.builder().id(id).namespace(namespace).content(content)
                .embedding(new float[]{1, 0, 0}).tags(List.of("profile")).tokenCount(10).build();
    }

    @Test
    void chineseBm25SearchesWholeCorpusAndPersistsAcrossReopen() throws Exception {
        try (var store = new LuceneMemoryStore(directory, 3)) {
            for (int i = 0; i < 300; i++) store.upsert(fragment("noise-" + i, "alice", "这是一条会议记录 " + i));
            store.upsert(fragment("coffee", "alice", "我喜欢喝美式咖啡，不加糖"));
            store.upsert(fragment("private", "bob", "咖啡咖啡喜欢美式咖啡"));
            assertThat(store.searchKeywords("用户喜欢什么咖啡", "alice", List.of("profile"), 5))
                    .extracting(hit -> hit.fragment().getId()).containsExactly("coffee");
            assertThat(store.searchKeywords("咖啡", "alice", List.of("other"), 5)).isEmpty();
            assertThat(store.searchKeywords("根本不存在的量子暗物质", "alice", List.of(), 5)).isEmpty();
        }
        try (var reopened = new LuceneMemoryStore(directory, 3)) {
            assertThat(reopened.searchKeywords("喜欢喝什么咖啡", "alice", List.of(), 1).getFirst().fragment().getId()).isEqualTo("coffee");
            assertThat(reopened.listByNamespace("alice", Integer.MAX_VALUE)).hasSize(301);
        }
    }

    @Test
    void vectorSearchFiltersNamespaceAndAllTagsBeforeTopK() throws Exception {
        try (var store = new LuceneMemoryStore(directory, 3)) {
            var wrongTag = fragment("wrong-tag", "alice", "unrelated");
            wrongTag.setTags(List.of("work"));
            store.upsert(wrongTag);
            store.upsert(fragment("other-user", "bob", "private"));
            var target = fragment("target", "alice", "coffee");
            target.setEmbedding(new float[]{0.9f, 0.1f, 0});
            store.upsert(target);
            assertThat(store.searchVectors(new float[]{1, 0, 0}, "alice", List.of("profile"), 1))
                    .extracting(MemoryFragment::getId).containsExactly("target");
        }
    }

    @Test
    void catalogPaginatesByKeyAndFiltersNamespaceAndLifecycleBeforeLimit() throws Exception {
        try (var store = new LuceneMemoryStore(directory, 3)) {
            for (String key : List.of("d", "c", "b", "a")) {
                store.putFact(key, fragment("unused", "alice", "事实 " + key), 0, null);
            }
            store.putFact("a0", fragment("unused", "bob", "other namespace"), 0, null);
            store.retractFact("alice", "a", 1);
            store.deleteFact("alice", "b", 1);
            assertThat(store.listFacts("alice", null, 1, false)).extracting(f -> f.key()).containsExactly("c");
            store.putFact("c", fragment("unused", "alice", "更新后的内容"), 1, null);
            assertThat(store.listFacts("alice", "c", 1, false)).extracting(f -> f.key()).containsExactly("d");
            assertThat(store.listFacts("alice", "d", 1, false)).isEmpty();
            assertThat(store.listFacts("alice", null, 100, true)).extracting(f -> f.key()).containsExactly("a", "c", "d");
            assertThat(store.listFacts("bob", null, 100, true)).extracting(f -> f.key()).containsExactly("a0");
        }
        try (var reopened = new LuceneMemoryStore(directory, 3)) {
            assertThat(reopened.listFacts("alice", null, 100, false)).extracting(f -> f.content())
                    .containsExactly("更新后的内容", "事实 d");
        }
    }

    @Test
    void upgradesVersionOneCatalogWithoutChangingFactsHistoryOrSearch() throws Exception {
        try (var store = new LuceneMemoryStore(directory, 3)) {
            store.putFact("city", fragment("unused", "alice", "我住北京"), 0, null);
            store.putFact("city", fragment("unused", "alice", "我住上海"), 1, null);
        }
        // Recreate the v1 current-fact layout, which had no namespace/key catalog fields.
        try (var dir = org.apache.lucene.store.FSDirectory.open(directory);
             var reader = org.apache.lucene.index.DirectoryReader.open(dir);
             var analyzer = new org.apache.lucene.analysis.cjk.CJKAnalyzer();
             var writer = new org.apache.lucene.index.IndexWriter(dir, new org.apache.lucene.index.IndexWriterConfig(analyzer))) {
            var searcher = new org.apache.lucene.search.IndexSearcher(reader);
            var query = new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term("kind", "fact"));
            var stored = reader.storedFields().document(searcher.search(query, 1).scoreDocs[0].doc);
            var legacy = new org.apache.lucene.document.Document();
            for (String field : List.of("id", "current")) {
                legacy.add(new org.apache.lucene.document.StringField(field, stored.get(field), org.apache.lucene.document.Field.Store.YES));
            }
            legacy.add(new org.apache.lucene.document.StringField("owner", stored.get("current"), org.apache.lucene.document.Field.Store.NO));
            legacy.add(new org.apache.lucene.document.StringField("kind", "fact", org.apache.lucene.document.Field.Store.NO));
            legacy.add(new org.apache.lucene.document.StoredField("payload", stored.getBinaryValue("payload")));
            writer.updateDocument(new org.apache.lucene.index.Term("id", stored.get("id")), legacy);
            writer.setLiveCommitData(java.util.Map.of("vortex.schema", "1", "vortex.dimension", "3").entrySet());
            writer.commit();
        }
        try (var upgraded = new LuceneMemoryStore(directory, 3)) {
            assertThat(upgraded.listFacts("alice", null, 30, false)).extracting(f -> f.content()).containsExactly("我住上海");
            assertThat(upgraded.getFact("alice", "city").orElseThrow().revision()).isEqualTo(2);
            assertThat(upgraded.factHistory("alice", "city", Long.MAX_VALUE, 20)).hasSize(2);
            assertThat(upgraded.searchKeywords("住上海", "alice", List.of(), 5)).hasSize(1);
            assertThat(upgraded.searchKeywords("住北京", "alice", List.of(), 5)).isEmpty();
        }
        try (var reopened = new LuceneMemoryStore(directory, 3)) {
            assertThat(reopened.listFacts("alice", null, 30, false)).hasSize(1);
        }
    }

    @Test
    void replacementIsAtomicAndLateWritesCannotResurrectOldFactsAfterRestart() throws Exception {
        MemoryFragment old = fragment("unused", "alice", "我住北京");
        try (var store = new LuceneMemoryStore(directory, 3)) {
            var first = store.putFact("home.city", old, 0, null);
            var second = store.putFact("home.city", fragment("unused", "alice", "搬到上海了"), 1, null);
            assertThat(first.revision()).isEqualTo(1);
            assertThat(second.revision()).isEqualTo(2);
            assertThat(store.isRecallable(first.fragmentId())).isFalse();
            store.archiveFragment(old);
            assertThat(store.get(first.fragmentId())).isEmpty();
            assertThat(store.search(new float[]{1, 0, 0}, "alice", 10)).extracting(MemoryFragment::getContent).containsExactly("搬到上海了");
            assertThat(store.factHistory("alice", "home.city", Long.MAX_VALUE, 20)).extracting(fact -> fact.revision()).containsExactly(2L, 1L);
            assertThat(store.factHistory("alice", "home.city", 2, 20)).extracting(fact -> fact.revision()).containsExactly(1L);
        }
        try (var reopened = new LuceneMemoryStore(directory, 3)) {
            reopened.upsert(old);
            assertThat(reopened.get(old.getId())).isEmpty();
            assertThat(reopened.getFact("alice", "home.city").orElseThrow().revision()).isEqualTo(2);
            assertThatThrownBy(() -> reopened.putFact("home.city", fragment("unused", "alice", "旧客户端写回北京"), 1, null))
                    .isInstanceOf(MemoryRevisionConflictException.class);
            assertThat(reopened.getFact("alice", "home.city").orElseThrow().content()).isEqualTo("搬到上海了");
        }
    }

    @Test
    void concurrentCompareAndSetAllowsExactlyOneWinner() throws Exception {
        try (var store = new LuceneMemoryStore(directory, 3); var pool = Executors.newFixedThreadPool(8)) {
            store.putFact("city", fragment("unused", "alice", "北京"), 0, null);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int index = i;
                tasks.add(pool.submit(() -> {
                    start.await();
                    try {
                        store.putFact("city", fragment("unused", "alice", "上海 " + index), 1, null);
                        return true;
                    } catch (MemoryRevisionConflictException expected) { return false; }
                }));
            }
            start.countDown();
            int winners = 0;
            for (var task : tasks) if (task.get(20, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
            assertThat(store.listByNamespace("alice", 100)).hasSize(1);
        }
    }

    @Test
    void retractionKeepsAuditWhileDeleteErasesHistoryAndPreventsStaleRecreation() throws Exception {
        try (var store = new LuceneMemoryStore(directory, 3)) {
            var original = fragment("unused", "alice", "私人电话号码 123456");
            var first = store.putFact("phone", original, 0, null);
            store.retractFact("alice", "phone", 1);
            assertThat(store.isRecallable(first.fragmentId())).isFalse();
            assertThat(store.factHistory("alice", "phone", Long.MAX_VALUE, 100)).hasSize(2);
            store.deleteFact("alice", "phone", 2);
            store.upsert(original);
            assertThat(store.get(first.fragmentId())).isEmpty();
            assertThat(store.factHistory("alice", "phone", Long.MAX_VALUE, 100)).hasSize(1)
                    .allMatch(fact -> fact.content() == null && fact.fragmentId() == null);
            assertThatThrownBy(() -> store.putFact("phone", fragment("unused", "alice", "stale"), 0, null))
                    .isInstanceOf(MemoryRevisionConflictException.class);
            assertThat(store.putFact("phone", fragment("unused", "alice", "new"), 3, null).revision()).isEqualTo(4);
        }
    }

    @Test
    void expiryExcludesBothSearchBranchesWithoutCleanupJob() throws Exception {
        try (var store = new LuceneMemoryStore(directory, 3)) {
            var fact = store.putFact("temporary", fragment("unused", "alice", "临时咖啡订单"), 0, Instant.now().plusMillis(500));
            long wait = Math.max(0, fact.expiresAt().toEpochMilli() - System.currentTimeMillis() + 20);
            Thread.sleep(wait);
            assertThat(store.isRecallable(fact.fragmentId())).isFalse();
            assertThat(store.searchKeywords("咖啡", "alice", List.of(), 5)).isEmpty();
            assertThat(store.search(new float[]{1, 0, 0}, "alice", 5)).isEmpty();
            assertThat(store.getFact("alice", "temporary")).isPresent();
            assertThat(store.listFacts("alice", null, 30, false)).isEmpty();
            assertThat(store.listFacts("alice", null, 30, true)).hasSize(1);
        }
    }

    @Test
    void deletedAppendMemoryCannotReturnFromLateArchiveAfterRestart() throws Exception {
        var original = fragment("append-id", "alice", "remove me");
        try (var store = new LuceneMemoryStore(directory, 3)) {
            store.upsert(original);
            store.delete(original.getId());
        }
        try (var store = new LuceneMemoryStore(directory, 3)) {
            store.archiveFragment(original);
            assertThat(store.get(original.getId())).isEmpty();
            assertThat(store.isRecallable(original.getId())).isFalse();
        }
    }

    @Test
    void checkpointsAndBinaryObjectsRoundTripAfterRestart() throws Exception {
        String checkpoint;
        try (var store = new LuceneMemoryStore(directory, 3)) {
            var state = TaskState.builder().taskId("task-a").namespace("alice").walSequenceNumber(7).build();
            state.getContext().put("result", "上海");
            checkpoint = store.saveCheckpoint(state);
            store.saveCheckpointBytesWithMetadata(new byte[]{1, 2, 3}, CheckpointMetadata.builder()
                    .taskId("task-a").checkpointId("delta").sequenceNumber(8).type(CheckpointMetadata.CheckpointType.DELTA).build());
            store.putBytes("system/state", new byte[]{3, 2, 1});
        }
        try (var store = new LuceneMemoryStore(directory, 3)) {
            assertThat(store.loadCheckpoint("task-a/" + checkpoint).orElseThrow().getContext()).containsEntry("result", "上海");
            assertThat(store.listCheckpointMetadata("task-a")).extracting(CheckpointMetadata::getSequenceNumber).containsExactly(7L, 8L);
            assertThat(store.listTaskIdsWithCheckpoints()).containsExactly("task-a");
            assertThat(store.getBytes("checkpoints/task-a/delta.kryo")).containsExactly(1, 2, 3);
            assertThat(store.getBytes("system/state")).containsExactly(3, 2, 1);
            store.deleteCheckpoint("task-a/" + checkpoint);
            assertThat(store.loadCheckpoint("task-a/" + checkpoint)).isEmpty();
        }
    }

    @Test
    void rejectsSecondWriterAndInvalidVectorsWithoutCorruptingData() throws Exception {
        try (var store = new LuceneMemoryStore(directory, 3)) {
            assertThatThrownBy(() -> new LuceneMemoryStore(directory, 3)).isInstanceOf(java.io.IOException.class);
            var bad = fragment("bad", "alice", "bad");
            bad.setEmbedding(new float[]{Float.NaN, 0, 0});
            assertThatThrownBy(() -> store.upsert(bad)).isInstanceOf(IllegalArgumentException.class);
            store.upsert(fragment("good", "alice", "valid"));
        }
        assertThatThrownBy(() -> new LuceneMemoryStore(directory, 2)).isInstanceOf(IllegalStateException.class);
        try (var store = new LuceneMemoryStore(directory, 3)) {
            assertThat(store.get("good")).isPresent();
        }
    }
}
