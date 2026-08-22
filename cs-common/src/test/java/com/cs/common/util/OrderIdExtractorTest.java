package com.cs.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderIdExtractorTest {

    @Test
    void extractNormalizesCase() {
        assertEquals("ORD20260609001", OrderIdExtractor.extract("ord20260609001"));
        assertEquals("ORD20260609001", OrderIdExtractor.extract("订单号是 ORD20260609001"));
        assertNull(OrderIdExtractor.extract("没有单号"));
    }

    @Test
    void providingOrderIdIsSlotFill() {
        assertTrue(OrderIdExtractor.isProvidingOrderId("ORD20260609001"));
        assertTrue(OrderIdExtractor.isProvidingOrderId("订单号是ORD20260609001"));
        assertTrue(OrderIdExtractor.isProvidingOrderId("就是这个 ORD20260609001"));
    }

    @Test
    void newBusinessIsNotSlotFill() {
        assertFalse(OrderIdExtractor.isProvidingOrderId("查物流 ORD20260609001"));
        assertFalse(OrderIdExtractor.isProvidingOrderId("退换货 ORD20260609001"));
        assertFalse(OrderIdExtractor.isProvidingOrderId("退款"));
        assertFalse(OrderIdExtractor.isProvidingOrderId("刚刚已经发给你了"));
    }
}
