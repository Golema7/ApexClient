package dev.apexclient;

import java.math.BigDecimal;

record MarketOpportunity(
        MarketListing purchase,
        BigDecimal conservativeProceeds,
        BigDecimal conservativeProfit,
        BigDecimal returnPercent,
        int matchedOrderCount,
        long oldestSourceCaptureMillis
) {
    boolean isFresh(long nowMillis) {
        return nowMillis - oldestSourceCaptureMillis <= MarketScanner.STALE_AFTER_MILLIS;
    }
}
