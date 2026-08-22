package com.cs.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedAgentContextTest {

    @Test
    void renderAndParseOrderIdAndSubIntent() {
        String text = SharedAgentContext.render(
                "ORD20260609001", "RETURN", "order", "after_sales", "user: 退换货\n");
        assertEquals("ORD20260609001", SharedAgentContext.orderIdOf(text));
        assertEquals("RETURN", SharedAgentContext.subIntentOf(text));
        assertTrue(text.contains("Agent 切换：order → after_sales"));
        assertTrue(SharedAgentContext.hasUsableContent(
                "ORD20260609001", "RETURN", "order", "after_sales", "user: 退换货\n"));
    }
}
