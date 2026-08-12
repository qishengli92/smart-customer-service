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
 * FAQ 知识库 Milvus 读写：建表、upsert、向量检索（collection 如 {@code cs_faq}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusKnowledgeStore {

    public static final String FIELD_CHUNK_ID = "chunk_id";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_CATEGORY = "category";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_EMBEDDING = "embedding";

    private final MilvusServiceClient milvusClient;
    private final MilvusProperties milvusProperties;

    public boolean isAvailable() {
        try {
            String collection = milvusProperties.faqCollectionName();
            R<Boolean> resp = milvusClient.hasCollection(
                    HasCollectionParam.newBuilder().withCollectionName(collection).build());
            return resp.getStatus() == R.Status.Success.getCode();
        } catch (Exception e) {
            log.warn("Milvus unavailable: {}", e.getMessage());
            return false;
        }
    }

    public synchronized void ensureCollection() {
        String collection = milvusProperties.faqCollectionName();
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
                FieldType.newBuilder()
                        .withName(FIELD_CHUNK_ID)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .withPrimaryKey(true)
                        .withAutoID(false)
                        .build(),
                FieldType.newBuilder()
                        .withName(FIELD_TITLE)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(256)
                        .build(),
                FieldType.newBuilder()
                        .withName(FIELD_CATEGORY)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(128)
                        .build(),
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
                        .withDescription("Smart CS FAQ knowledge chunks")
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

    /**
     * Upsert FAQ chunks（按 chunk_id 幂等）。
     */
    public int upsertChunks(List<KnowledgeChunk> chunks, List<float[]> embeddings) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        if (embeddings == null || embeddings.size() != chunks.size()) {
            throw new IllegalArgumentException("embeddings size must match chunks");
        }
        ensureCollection();
        String collection = milvusProperties.faqCollectionName();

        List<String> ids = new ArrayList<>(chunks.size());
        List<String> titles = new ArrayList<>(chunks.size());
        List<String> categories = new ArrayList<>(chunks.size());
        List<String> contents = new ArrayList<>(chunks.size());
        List<List<Float>> vectors = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            ids.add(chunk.getChunkId());
            titles.add(nullToEmpty(chunk.getSourceDoc()));
            String category = chunk.getMetadata() != null
                    ? chunk.getMetadata().getOrDefault("category", "") : "";
            categories.add(category);
            contents.add(nullToEmpty(chunk.getContent()));
            vectors.add(toFloatList(embeddings.get(i)));
        }

        List<InsertParam.Field> fields = Arrays.asList(
                new InsertParam.Field(FIELD_CHUNK_ID, ids),
                new InsertParam.Field(FIELD_TITLE, titles),
                new InsertParam.Field(FIELD_CATEGORY, categories),
                new InsertParam.Field(FIELD_CONTENT, contents),
                new InsertParam.Field(FIELD_EMBEDDING, vectors)
        );

        R<MutationResult> upsert = milvusClient.upsert(
                UpsertParam.newBuilder()
                        .withCollectionName(collection)
                        .withFields(fields)
                        .build());
        handle(upsert, "upsert");

        milvusClient.flush(FlushParam.newBuilder()
                .withCollectionNames(List.of(collection))
                .build());
        loadCollection(collection);

        long n = upsert.getData() != null ? upsert.getData().getUpsertCnt() : chunks.size();
        log.info("Upserted {} FAQ chunks into Milvus collection {}", n, collection);
        return (int) n;
    }

    /**
     * 兼容无 upsert 的环境：退化为 insert（调用方需保证不重复）。
     */
    public int insertChunks(List<KnowledgeChunk> chunks, List<float[]> embeddings) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        ensureCollection();
        String collection = milvusProperties.faqCollectionName();

        List<String> ids = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            ids.add(chunk.getChunkId());
            titles.add(nullToEmpty(chunk.getSourceDoc()));
            String category = chunk.getMetadata() != null
                    ? chunk.getMetadata().getOrDefault("category", "") : "";
            categories.add(category);
            contents.add(nullToEmpty(chunk.getContent()));
            vectors.add(toFloatList(embeddings.get(i)));
        }

        R<MutationResult> insert = milvusClient.insert(
                InsertParam.newBuilder()
                        .withCollectionName(collection)
                        .withFields(Arrays.asList(
                                new InsertParam.Field(FIELD_CHUNK_ID, ids),
                                new InsertParam.Field(FIELD_TITLE, titles),
                                new InsertParam.Field(FIELD_CATEGORY, categories),
                                new InsertParam.Field(FIELD_CONTENT, contents),
                                new InsertParam.Field(FIELD_EMBEDDING, vectors)
                        ))
                        .build());
        handle(insert, "insert");
        milvusClient.flush(FlushParam.newBuilder()
                .withCollectionNames(List.of(collection))
                .build());
        return chunks.size();
    }

    public List<KnowledgeChunk> search(float[] queryVector, int topK, double threshold) {
        ensureCollection();
        String collection = milvusProperties.faqCollectionName();
        loadCollection(collection);

        List<List<Float>> vectors = List.of(toFloatList(queryVector));
        R<SearchResults> resp = milvusClient.search(
                SearchParam.newBuilder()
                        .withCollectionName(collection)
                        .withMetricType(MetricType.COSINE)
                        .withTopK(topK)
                        .withVectors(vectors)
                        .withVectorFieldName(FIELD_EMBEDDING)
                        .withOutFields(List.of(FIELD_CHUNK_ID, FIELD_TITLE, FIELD_CATEGORY, FIELD_CONTENT))
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
            String title = (String) wrapper.getFieldData(FIELD_TITLE, 0).get(i);
            String category = (String) wrapper.getFieldData(FIELD_CATEGORY, 0).get(i);
            String content = (String) wrapper.getFieldData(FIELD_CONTENT, 0).get(i);
            Map<String, String> meta = new HashMap<>();
            if (category != null && !category.isBlank()) {
                meta.put("category", category);
            }
            hits.add(KnowledgeChunk.builder()
                    .chunkId(chunkId)
                    .sourceDoc(title)
                    .content(content)
                    .score(score)
                    .metadata(meta)
                    .build());
        }
        return hits;
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
            // 已 load 时忽略
            log.debug("loadCollection {}: {}", collection, e.getMessage());
        }
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

    private static <T> void handle(R<T> response, String op) {
        if (response == null) {
            throw new IllegalStateException("Milvus " + op + " returned null");
        }
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus " + op + " failed: " + response.getMessage());
        }
    }
}
