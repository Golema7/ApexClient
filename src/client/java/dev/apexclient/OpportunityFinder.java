package dev.apexclient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

final class OpportunityFinder {
    private static final int MIN_COMPARABLE_LISTINGS = 2;

    private OpportunityFinder() {
    }

    static List<MarketOpportunity> rank(Collection<PageSnapshot> pages, long nowMillis) {
        List<MarketListing> auctionListings = new ArrayList<>();
        for (PageSnapshot page : pages) {
            if (page.side() == MarketSide.AUCTION
                    && nowMillis - page.capturedAtMillis() <= MarketScanner.STALE_AFTER_MILLIS) {
                auctionListings.addAll(page.listings());
            }
        }

        List<MarketOpportunity> opportunities = new ArrayList<>();
        for (MarketListing candidate : auctionListings) {
            if (candidate.quantity() <= 0) {
                continue;
            }

            BigDecimal comparableUnitPriceSum = BigDecimal.ZERO;
            int comparableCount = 0;
            long oldestCapture = candidate.capturedAtMillis();
            for (MarketListing comparable : auctionListings) {
                if (comparable.quantity() <= 0
                        || !comparable.identityKey().equals(candidate.identityKey())
                        || (comparable.page() == candidate.page() && comparable.slot() == candidate.slot())) {
                    continue;
                }

                BigDecimal unitPrice = comparable.priceLowerBound()
                        .divide(BigDecimal.valueOf(comparable.quantity()), 8, RoundingMode.DOWN);
                comparableUnitPriceSum = comparableUnitPriceSum.add(unitPrice);
                comparableCount++;
                oldestCapture = Math.min(oldestCapture, comparable.capturedAtMillis());
            }

            if (comparableCount < MIN_COMPARABLE_LISTINGS) {
                continue;
            }

            BigDecimal averageUnitPrice = comparableUnitPriceSum
                    .divide(BigDecimal.valueOf(comparableCount), 8, RoundingMode.DOWN);
            BigDecimal estimatedSaleTotal = averageUnitPrice
                    .multiply(BigDecimal.valueOf(candidate.quantity()))
                    .setScale(0, RoundingMode.DOWN);
            BigDecimal estimatedProfit = estimatedSaleTotal.subtract(candidate.priceUpperBound());
            if (estimatedProfit.signum() <= 0) {
                continue;
            }

            BigDecimal returnPercent = estimatedProfit
                    .multiply(BigDecimal.valueOf(100))
                    .divide(candidate.priceUpperBound().max(BigDecimal.ONE), 1, RoundingMode.HALF_UP);
            opportunities.add(new MarketOpportunity(
                    candidate,
                    estimatedSaleTotal,
                    estimatedProfit,
                    returnPercent,
                    comparableCount,
                    oldestCapture
            ));
        }

        opportunities.sort(Comparator
                .comparing(MarketOpportunity::conservativeProfit)
                .reversed()
                .thenComparing(MarketOpportunity::returnPercent, Comparator.reverseOrder()));
        return List.copyOf(opportunities);
    }
}
