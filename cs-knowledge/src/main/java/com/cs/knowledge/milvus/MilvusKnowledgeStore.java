package com.cs.knowledge.milvus;

import com.cs.infra.config.MilvusProperties;
import com.cs.knowledge.retrieval.KnowledgeChunk;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库 Milvus 读写：collection {@code cs_knowledge}，按 chunk 存储、按 doc_id 删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusKnowledgeStore {

    public static final String FIELD_CHUNK_ID = "chunk_id";
    public static final String FIELD_DOC_ID = "doc_id";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_CATEGORY = "category";
    public static final String FIELD_HEADING = "heading";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_EMBEDDING = "embedding";

    private final MilvusServiceClient milvusClient;
    private final MilvusProperties milvusProperties;

    public boolean isAvailable() {
        try {
            String collection = collectionName();
            R<Boolean> resp = milvusClient.hasCollection(
                    HasCollectionParam.newBuilder().withCollectionName(collection).build());
            return resp.getStatus() == R.Status.Success.getCode();
        } catch (Exception e) {
            log.warn("Milvus unavailable: {}", e.getMessage());
            return false;
        }
    }

    public synchronized void ensureCollection() {
        String collection = collectionName();
        int dim = milvusProperties.getEmbeddingDimension();

        R<Boolean> has = milvusClient.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collection).build());
        handle(has, "hasCollection");
        if (Boolean.TRUE.equals(has.getData())) {
            loadCollection(collection);
            return;
        }

        log.info("Creating Milvus collection: {}, dim={}", collection, dim);
        List<FieldType> fields = List.of(
                varcharPk(FIELD_CHUNK_ID, 64),
                varchar(FIELD_DOC_ID, 64),
                varchar(FIELD_TITLE, 512),
                varchar(FIELD_CATEGORY, 128),
                varchar(FIELD_HEADING, 512),
                FieldType.newBuilder()
                        .withName(FIELD_CONTENT)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(8192)
                        .build(),
                FieldType.newBuilder()
                        .withName(FIELD_EMBEDDING)
                        .withDataType(DataType.FloatVector)
                        .withDimension(dim)
                        .build()
        );

        R<RpcStatus> create = milvusClient.createCollection(
                CreateCollectionParam.newBuilder()
                        .withCollectionName(collection)
                        .withDescription("Smart CS knowledge chunks")
                        .withFieldTypes(fields)
                        .build());
        handle(create, "createCollection");

        R<RpcStatus> index = milvusClient.createIndex(
                CreateIndexParam.newBuilder()
                        .withCollectionName(collection)
                        .withFieldName(FIELD_EMBEDDING)
                        .withIndexType(IndexType.AUTOINDEX)
                        .withMetricType(MetricType.COSINE)
                        .build());
        handle(index, "createIndex");

        loadCollection(collection);
        log.info("Milvus collection ready: {}", collection);
    }

    public int upsertChunks(List<KnowledgeChunk> chunks, List<float[]> embeddings) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        if (embeddings == null || embeddings.size() != chunks.size()) {
            throw new IllegalArgumentException("embeddings size must match chunks");
        }
        ensureCollection();
        String collection = collectionName();

        List<String> ids = new ArrayList<>(chunks.size());
        List<String> docIds = new ArrayList<>(chunks.size());
        List<String> titles = new ArrayList<>(chunks.size());
        List<String> categories = new ArrayList<>(chunks.size());
        List<String> headings = new ArrayList<>(chunks.size());
        List<String> contents = new ArrayList<>(chunks.size());
        List<List<Float>> vectors = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            ids.add(chunk.getChunkId());
            docIds.add(nullToEmpty(chunk.getDocId()));
            titles.add(truncate(nullToEmpty(chunk.getSourceDoc()), 512));
            String category = chunk.getMetadata() != null
                    ? chunk.getMetadata().getOrDefault("category", "") : "";
            categories.add(truncate(category, 128));
            headings.add(truncate(nullToEmpty(chunk.getHeading()), 512));
            contents.add(truncate(nullToEmpty(chunk.getContent()), 8192));
            vectors.add(toFloatList(embeddings.get(i)));
        }

        List<InsertParam.Field> fields = Arrays.asList(
                new InsertParam.Field(FIELD_CHUNK_ID, ids),
                new InsertParam.Field(FIELD_DOC_ID, docIds),
                new InsertParam.Field(FIELD_TITLE, titles),
                new InsertParam.Field(FIELD_CATEGORY, categories),
                new InsertParam.Field(FIELD_HEADING, headings),
                new InsertParam.Field(FIELD_CONTENT, contents),
                new InsertParam.Field(FIELD_EMBEDDING, vectors)
        );

        R<MutationResult> upsert = retryOnRateLimit("upsert", () -> {
            R<MutationResult> resp = milvusClient.upsert(
                    UpsertParam.newBuilder()
                            .withCollectionName(collection)
                            .withFields(fields)
                            .build());
            handle(resp, "upsert");
            return resp;
        });

        long n = upsert.getData() != null ? upsert.getData().getUpsertCnt() : chunks.size();
        log.info("Upserted {} knowledge chunks into {}", n, collection);
        return (int) n;
    }

    public void deleteByDocId(String docId) {
        if (docId == null || docId.isBlank()) {
            return;
        }
        ensureCollection();
        String collection = collectionName();
        String expr = FIELD_DOC_ID + " == \"" + escapeExpr(docId) + "\"";
        retryOnRateLimit("delete", () -> {
            R<MutationResult> resp = milvusClient.delete(
                    DeleteParam.newBuilder()
                            .withCollectionName(collection)
                            .withExpr(expr)
                            .build());
            handle(resp, "delete");
            return resp;
        });
        log.info("Deleted Milvus chunks for doc_id={}", docId);
    }

    /**
     * 批量入库结束后再 flush。单次 upsert/delete 不 flush：Milvus 默认 flush 限流约 0.1/s，
     * 启动种子连刷会直接被 RateLimiter 拒绝。WAL 已可被 BOUNDED 检索看到。
     */
    public void flushQuietly() {
        try {
            ensureCollection();
            String collection = collectionName();
            retryOnRateLimit("flush", () -> {
                R<?> resp = milvusClient.flush(FlushParam.newBuilder()
                        .withCollectionNames(List.of(collection))
                        .build());
                handle(resp, "flush");
                return resp;
            });
        } catch (Exception e) {
            log.warn("Milvus flush skipped (search still works on growing segments): {}", e.getMessage());
        }
    }

    public List<KnowledgeChunk> search(float[] queryVector, int topK, double threshold) {
        ensureCollection();
        String collection = collectionName();
        loadCollection(collection);

        List<List<Float>> vectors = List.of(toFloatList(queryVector));
        R<SearchResults> resp = milvusClient.search(
                SearchParam.newBuilder()
                        .withCollectionName(collection)
                        .withMetricType(MetricType.COSINE)
                        .withTopK(Math.max(1, topK))
                        .withVectors(vectors)
                        .withVectorFieldName(FIELD_EMBEDDING)
                        .withOutFields(List.of(FIELD_CHUNK_ID, FIELD_DOC_ID, FIELD_TITLE,
                                FIELD_CATEGORY, FIELD_HEADING, FIELD_CONTENT))
                        .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                        .build());
        handle(resp, "search");

        SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
        List<KnowledgeChunk> hits = new ArrayList<>();
        List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
        for (int i = 0; i < scores.size(); i++) {
            float score = scores.get(i).getScore();
            if (score < threshold) {
                continue;
            }
            String chunkId = (String) wrapper.getFieldData(FIELD_CHUNK_ID, 0).get(i);
            String docId = (String) wrapper.getFieldData(FIELD_DOC_ID, 0).get(i);
            String title = (String) wrapper.getFieldData(FIELD_TITLE, 0).get(i);
            String category = (String) wrapper.getFieldData(FIELD_CATEGORY, 0).get(i);
            String heading = (String) wrapper.getFieldData(FIELD_HEADING, 0).get(i);
            String content = (String) wrapper.getFieldData(FIELD_CONTENT, 0).get(i);
            Map<String, String> meta = new HashMap<>();
            if (category != null && !category.isBlank()) {
                meta.put("category", category);
            }
            hits.add(KnowledgeChunk.builder()
                    .chunkId(chunkId)
                    .docId(docId)
                    .sourceDoc(title)
                    .heading(heading)
                    .content(content)
                    .score(score)
                    .vectorScore(score)
                    .metadata(meta)
                    .build());
        }
        return hits;
    }

    private String collectionName() {
        return milvusProperties.knowledgeCollectionName();
    }

    private void loadCollection(String collection) {
        try {
            milvusClient.loadCollection(
                    LoadCollectionParam.newBuilder()
                            .withCollectionName(collection)
                            .withSyncLoad(true)
                            .withSyncLoadWaitingTimeout(30L)
                            .withSyncLoadWaitingInterval(500L)
                            .build());
        } catch (Exception e) {
            log.debug("loadCollection {}: {}", collection, e.getMessage());
        }
    }

    private static FieldType varcharPk(String name, int max) {
        return FieldType.newBuilder()
                .withName(name)
                .withDataType(DataType.VarChar)
                .withMaxLength(max)
                .withPrimaryKey(true)
                .withAutoID(false)
                .build();
    }

    private static FieldType varchar(String name, int max) {
        return FieldType.newBuilder()
                .withName(name)
                .withDataType(DataType.VarChar)
                .withMaxLength(max)
                .build();
    }

    private static List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String escapeExpr(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private <T> T retryOnRateLimit(String op, java.util.function.Supplier<T> action) {
        int attempts = 4;
        long backoffMs = 2500L;
        for (int i = 1; i <= attempts; i++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (!isRateLimited(e) || i == attempts) {
                    throw e;
                }
                log.warn("Milvus {} rate-limited, retry {}/{} after {}ms: {}",
                        op, i, attempts, backoffMs, e.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                backoffMs = Math.min(backoffMs * 2, 12_000L);
            }
        }
        throw new IllegalStateException("Milvus " + op + " failed after retries");
    }

    private static boolean isRateLimited(Throwable e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("RateLimiter") || msg.contains("rate limit"));
    }

    private static <T> void handle(R<T> response, String op) {
        if (response == null) {
            throw new IllegalStateException("Milvus " + op + " returned null");
        }
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus " + op + " failed: " + response.getMessage());
        }
    }
}
