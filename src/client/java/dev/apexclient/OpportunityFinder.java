package dev.apexclient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpportunityFinder {
    private OpportunityFinder() {
    }

    static List<MarketOpportunity> rank(Collection<PageSnapshot> pages, long nowMillis) {
        Map<String, List<MarketListing>> purchasesByItem = new HashMap<>();
        Map<String, List<MarketListing>> ordersByItem = new HashMap<>();

        for (PageSnapshot page : pages) {
            if (nowMillis - page.capturedAtMillis() > MarketScanner.STALE_AFTER_MILLIS) {
                continue;
            }
            for (MarketListing listing : page.listings()) {
                if (listing.side() == MarketSide.AUCTION && listing.quantity() > 0) {
                    purchasesByItem.computeIfAbsent(listing.identityKey(), ignored -> new ArrayList<>()).add(listing);
                } else if (listing.side() == MarketSide.ORDER && listing.quantity() > 0) {
                    ordersByItem.computeIfAbsent(listing.identityKey(), ignored -> new ArrayList<>()).add(listing);
                }
            }
        }

        List<MarketOpportunity> opportunities = new ArrayList<>();
        for (Map.Entry<String, List<MarketListing>> entry : purchasesByItem.entrySet()) {
            List<MarketListing> purchases = entry.getValue();
            List<MarketListing> orders = ordersByItem.get(entry.getKey());
            if (orders == null || orders.isEmpty()) {
                continue;
            }

            purchases.sort(Comparator
                    .comparing((MarketListing listing) -> listing.priceUpperBound()
                            .divide(BigDecimal.valueOf(listing.quantity()), 6, RoundingMode.CEILING))
                    .thenComparingInt(MarketListing::page)
                    .thenComparingInt(MarketListing::slot));
            orders.sort(Comparator
                    .comparing(MarketListing::priceLowerBound)
                    .reversed()
                    .thenComparingInt(MarketListing::page)
                    .thenComparingInt(MarketListing::slot));

            Map<MarketListing, Integer> remainingOrderCapacity = new LinkedHashMap<>();
            for (MarketListing order : orders) {
                remainingOrderCapacity.put(order, order.quantity());
            }

            for (MarketListing purchase : purchases) {
                int remainingToSell = purchase.quantity();
                BigDecimal proceeds = BigDecimal.ZERO;
                long oldestCapture = purchase.capturedAtMillis();
                List<OrderAllocation> allocations = new ArrayList<>();

                for (MarketListing order : orders) {
                    if (remainingToSell == 0) {
                        break;
                    }

                    int orderCapacity = remainingOrderCapacity.getOrDefault(order, 0);
                    int allocation = Math.min(orderCapacity, remainingToSell);
                    if (allocation <= 0) {
                        continue;
                    }

                    allocations.add(new OrderAllocation(order, allocation));
                    proceeds = proceeds.add(order.priceLowerBound().multiply(BigDecimal.valueOf(allocation)));
                    remainingToSell -= allocation;
                    oldestCapture = Math.min(oldestCapture, order.capturedAtMillis());
                }

                if (remainingToSell != 0) {
                    continue;
                }

                BigDecimal profit = proceeds.subtract(purchase.priceUpperBound());
                if (profit.signum() <= 0) {
                    continue;
                }

                for (OrderAllocation allocation : allocations) {
                    remainingOrderCapacity.computeIfPresent(
                            allocation.order(),
                            (ignored, capacity) -> capacity - allocation.quantity()
                    );
                }

                BigDecimal returnPercent = profit
                        .multiply(BigDecimal.valueOf(100))
                        .divide(purchase.priceUpperBound().max(BigDecimal.ONE), 1, RoundingMode.HALF_UP);
                opportunities.add(new MarketOpportunity(
                        purchase,
                        proceeds,
                        profit,
                        returnPercent,
                        allocations.size(),
                        oldestCapture
                ));
            }
        }

        opportunities.sort(Comparator
                .comparing(MarketOpportunity::conservativeProfit)
                .reversed()
                .thenComparing(MarketOpportunity::returnPercent, Comparator.reverseOrder()));
        return List.copyOf(opportunities);
    }

    private record OrderAllocation(MarketListing order, int quantity) {
    }
}
