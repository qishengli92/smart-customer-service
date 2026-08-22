package com.cs.agents;

import com.cs.common.enums.IntentType;
import com.cs.common.model.RoutingDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RouterAgentTest {

    private RouterAgent router;

    @BeforeEach
    void setUp() {
        router = new RouterAgent();
    }

    @Test
    void afterSalesFromReturnExchange() {
        RoutingDecision decision = router.route("退换货");
        assertEquals(IntentType.AFTER_SALES, decision.getIntent());
        assertEquals("RETURN", decision.getSubIntent());
        assertEquals("RETURN", RouterAgent.resolveAfterSalesSubIntent("退换货"));
        assertEquals("EXCHANGE", RouterAgent.resolveAfterSalesSubIntent("换货"));
    }

    @Test
    void bareOrderIdIsNotOrderIntent() {
        RoutingDecision decision = router.route("ORD20260609001");
        assertEquals(IntentType.CHITCHAT, decision.getIntent());
        assertNull(decision.getEntities().get("orderId"));
    }

    @Test
    void providingOrderIdPhraseIsNotOrderIntent() {
        assertEquals(IntentType.CHITCHAT, router.hintIntent("订单号是ORD20260609001"));
    }

    @Test
    void logisticsWithOrderIdIsOrderIntent() {
        RoutingDecision decision = router.route("查物流 ORD20260609001");
        assertEquals(IntentType.ORDER, decision.getIntent());
        assertEquals("ORD20260609001", decision.getEntities().get("orderId"));
    }

    @Test
    void returnWithOrderIdStaysAfterSales() {
        RoutingDecision decision = router.route("退货 ORD20260609001");
        assertEquals(IntentType.AFTER_SALES, decision.getIntent());
        assertEquals("ORD20260609001", decision.getEntities().get("orderId"));
    }
}
