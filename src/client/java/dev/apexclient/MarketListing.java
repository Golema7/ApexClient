package dev.apexclient;

import java.math.BigDecimal;

record MarketListing(
        MarketSide side,
        String itemId,
        String itemName,
        String identityKey,
        int quantity,
        BigDecimal displayedPrice,
        BigDecimal priceLowerBound,
        BigDecimal priceUpperBound,
        boolean approximatePrice,
        int page,
        int slot,
        int delivered,
        int totalOrderQuantity,
        long capturedAtMillis
) {
}
