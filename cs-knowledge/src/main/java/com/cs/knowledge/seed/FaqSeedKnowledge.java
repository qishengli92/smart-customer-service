package com.cs.knowledge.seed;

import com.cs.knowledge.retrieval.KnowledgeChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MVP 种子 FAQ：不依赖 Embedding / Milvus 也能跑通知识问答主流程。
 */
public final class FaqSeedKnowledge {

    private record FaqDoc(String id, String title, String category, String content, String... keywords) {}

    private static final List<FaqDoc> DOCS = List.of(
            new FaqDoc("K400001", "退货退款政策", "售后政策",
                    """
                    退货退款政策：
                    1. 商品签收后7天内可申请无理由退货
                    2. 退货商品需保持原包装完好，配件齐全
                    3. 退款将在收到退货商品后3个工作日内原路退回
                    4. 定制商品、生鲜食品不支持7天无理由退货
                    5. 超过7天但未超过15天的，可申请售后维修
                    """,
                    "退货", "退款", "售后", "七天", "7天", "无理由"),
            new FaqDoc("K400002", "会员等级权益", "会员体系",
                    """
                    会员等级权益：
                    - VIP1: 9.8折优惠 + 免费标准配送
                    - VIP2: 9.5折优惠 + 免费急速配送 + 专属客服
                    - VIP3: 9折优惠 + 免费急速配送 + 专属客服 + 生日礼包
                    - VIP4: 8.5折优惠 + 免费当日达 + 7×24专属客服
                    - VIP5: 8折优惠 + 免费当日达 + 年度大礼包
                    """,
                    "会员", "vip", "权益", "折扣"),
            new FaqDoc("K400003", "配送时效说明", "物流配送",
                    """
                    配送时效说明：
                    1. 标准配送：2-5个工作日
                    2. 急速配送：1-2个工作日（VIP2及以上免费）
                    3. 当日达：当天下单当天送达（仅限部分城市）
                    4. 偏远地区可能额外增加1-3个工作日
                    """,
                    "配送", "物流", "时效", "发货", "快递多久"),
            new FaqDoc("K400004", "发票开具说明", "财务政策",
                    """
                    发票开具说明：
                    1. 订单完成后可在「我的订单-申请开票」提交
                    2. 支持电子普通发票与增值税专用发票
                    3. 电子发票一般1个工作日内发送至预留邮箱
                    4. 专票需提供完整公司抬头与税号，纸质票邮寄约3-5个工作日
                    """,
                    "发票", "开票", "开具", "税号", "专票", "电子发票"),
            new FaqDoc("K400005", "保修政策", "售后政策",
                    """
                    保修政策：
                    1. 数码类产品自签收起享12个月全国联保
                    2. 人为损坏、进液、私自拆修不在保修范围
                    3. 保修期内可到授权网点免费检修
                    4. 超保可付费维修，备件费用以网点报价为准
                    """,
                    "保修", "质保", "维修", "联保", "坏了"),
            new FaqDoc("K400006", "智能蓝牙耳机 Pro 常见问题", "产品FAQ",
                    """
                    智能蓝牙耳机 Pro 常见问题：
                    Q: 如何开启ANC降噪？
                    A: 长按左耳触控区2秒，听到提示音后切换降噪模式。
                    Q: 续航时间？
                    A: ANC开启约28小时，关闭约40小时。
                    Q: 防水等级？
                    A: IPX5，可防汗防小雨，不建议游泳佩戴。
                    """,
                    "耳机", "降噪", "anc", "续航", "蓝牙", "怎么用")
    );

    private FaqSeedKnowledge() {}

    /**
     * 全部种子 FAQ（用于写入 Milvus）
     */
    public static List<KnowledgeChunk> allChunks() {
        return DOCS.stream().map(doc -> KnowledgeChunk.builder()
                .chunkId(doc.id)
                .sourceDoc(doc.title)
                .content(doc.content.trim())
                .score(1.0f)
                .metadata(java.util.Map.of("category", doc.category))
                .build()).toList();
    }

    public static List<KnowledgeChunk> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<Scored> scored = new ArrayList<>();
        for (FaqDoc doc : DOCS) {
            float score = score(doc, q);
            if (score > 0) {
                scored.add(new Scored(doc, score));
            }
        }
        scored.sort((a, b) -> Float.compare(b.score, a.score));
        return scored.stream().limit(topK).map(s -> KnowledgeChunk.builder()
                .chunkId(s.doc.id)
                .sourceDoc(s.doc.title)
                .content(s.doc.content.trim())
                .score(s.score)
                .metadata(java.util.Map.of("category", s.doc.category))
                .build()).toList();
    }

    private static float score(FaqDoc doc, String query) {
        float score = 0f;
        String title = doc.title.toLowerCase(Locale.ROOT);
        String content = doc.content.toLowerCase(Locale.ROOT);
        if (title.contains(query) || query.contains(title)) {
            score += 0.5f;
        }
        for (String kw : doc.keywords) {
            String k = kw.toLowerCase(Locale.ROOT);
            if (query.contains(k)) {
                score += 0.35f;
            }
        }
        // 分词粗匹配：用户问题中的常见字词命中正文
        for (String token : query.split("[\\s，。？?！!、]+")) {
            if (token.length() >= 2 && (title.contains(token) || content.contains(token))) {
                score += 0.08f;
            }
        }
        return Math.min(score, 1.0f);
    }

    private record Scored(FaqDoc doc, float score) {}
}
