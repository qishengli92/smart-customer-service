package com.cs.knowledge.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 面向前端 / Prompt 的编号引用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeCitation {

    private int index;
    private String chunkId;
    private String docId;
    private String title;
    private String heading;
    private String category;
    private Float score;
}
