package com.cs.memory.milvus;

import com.cs.infra.config.MilvusProperties;
import com.cs.memory.longterm.MemoryRecord;
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
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 长期记忆 Milvus 读写（collection {@code cs_memory}）：按 userId 隔离向量检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusMemoryStore {

    public static final String FIELD_RECORD_ID = "record_id";
    public static final String FIELD_USER_ID = "user_id";
    public static final String FIELD_MEM_TYPE = "mem_type";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_EMBEDDING = "embedding";

    private final MilvusServiceClient milvusClient;
    private final MilvusProperties milvusProperties;

    public boolean isAvailable() {
        try {
            R<Boolean> resp = milvusClient.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withCollectionName(milvusProperties.memoryCollectionName())
                            .build());
            return resp.getStatus() == R.Status.Success.getCode();
        } catch (Exception e) {
            log.warn("Milvus memory store unavailable: {}", e.getMessage());
            return false;
        }
    }

    public synchronized void ensureCollection() {
        String collection = milvusProperties.memoryCollectionName();
        int dim = milvusProperties.getEmbeddingDimension();

        R<Boolean> has = milvusClient.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collection).build());
        handle(has, "hasCollection");
        if (Boolean.TRUE.equals(has.getData())) {
            loadCollection(collection);
            return;
        }

        log.info("Creating Milvus memory collection: {}, dim={}", collection, dim);
        List<FieldType> fields = List.of(
                FieldType.newBuilder()
                        .withName(FIELD_RECORD_ID)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .withPrimaryKey(true)
                        .withAutoID(false)
                        .build(),
                FieldType.newBuilder()
                        .withName(FIELD_USER_ID)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .build(),
                FieldType.newBuilder()
                        .withName(FIELD_MEM_TYPE)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
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
                        .withDescription("Smart CS long-term memory")
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
    }

    public void insert(MemoryRecord record, float[] embedding) {
        if (record == null || embedding == null || embedding.length == 0) {
            return;
        }
        ensureCollection();
        String collection = milvusProperties.memoryCollectionName();

        R<MutationResult> insert = milvusClient.insert(
                InsertParam.newBuilder()
                        .withCollectionName(collection)
                        .withFields(Arrays.asList(
                                new InsertParam.Field(FIELD_RECORD_ID, List.of(record.getRecordId())),
                                new InsertParam.Field(FIELD_USER_ID, List.of(nullToEmpty(record.getUserId()))),
                                new InsertParam.Field(FIELD_MEM_TYPE, List.of(nullToEmpty(record.getType()))),
                                new InsertParam.Field(FIELD_CONTENT, List.of(nullToEmpty(record.getContent()))),
                                new InsertParam.Field(FIELD_EMBEDDING, List.of(toFloatList(embedding)))
                        ))
                        .build());
        handle(insert, "insert");
        milvusClient.flush(FlushParam.newBuilder()
                .withCollectionNames(List.of(collection))
                .build());
    }

    public List<MemoryRecord> search(String userId, float[] queryVector, int topK, double threshold) {
        if (queryVector == null || queryVector.length == 0) {
            return Collections.emptyList();
        }
        ensureCollection();
        String collection = milvusProperties.memoryCollectionName();
        loadCollection(collection);

        String expr = userId == null || userId.isBlank()
                ? null
                : FIELD_USER_ID + " == \"" + userId.replace("\"", "") + "\"";

        SearchParam.Builder builder = SearchParam.newBuilder()
                .withCollectionName(collection)
                .withMetricType(MetricType.COSINE)
                .withTopK(topK)
                .withVectors(List.of(toFloatList(queryVector)))
                .withVectorFieldName(FIELD_EMBEDDING)
                .withOutFields(List.of(FIELD_RECORD_ID, FIELD_USER_ID, FIELD_MEM_TYPE, FIELD_CONTENT))
                .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED);
        if (expr != null) {
            builder.withExpr(expr);
        }

        R<SearchResults> resp = milvusClient.search(builder.build());
        handle(resp, "search");

        SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
        List<MemoryRecord> hits = new ArrayList<>();
        List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
        for (int i = 0; i < scores.size(); i++) {
            float score = scores.get(i).getScore();
            if (score < threshold) {
                continue;
            }
            hits.add(MemoryRecord.builder()
                    .recordId((String) wrapper.getFieldData(FIELD_RECORD_ID, 0).get(i))
                    .userId((String) wrapper.getFieldData(FIELD_USER_ID, 0).get(i))
                    .type((String) wrapper.getFieldData(FIELD_MEM_TYPE, 0).get(i))
                    .content((String) wrapper.getFieldData(FIELD_CONTENT, 0).get(i))
                    .score(score)
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
            log.debug("loadCollection {}: {}", collection, e.getMessage());
        }
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static <T> void handle(R<T> resp, String op) {
        if (resp.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Milvus " + op + " failed: " + resp.getMessage());
        }
    }
}
