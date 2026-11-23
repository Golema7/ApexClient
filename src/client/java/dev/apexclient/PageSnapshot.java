package dev.apexclient;

import java.util.List;

record PageSnapshot(
        MarketSide side,
        int page,
        int fingerprint,
        int slotsScanned,
        long capturedAtMillis,
        List<MarketListing> listings
) {
    PageSnapshot {
        listings = List.copyOf(listings);
    }
}
