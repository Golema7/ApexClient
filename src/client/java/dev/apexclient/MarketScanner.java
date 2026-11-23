package dev.apexclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarketScanner {
    static final long STALE_AFTER_MILLIS = 120_000L;
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int PLAYER_INVENTORY_SLOTS = 36;
    private static final int MAX_PAGES_PER_SIDE = 30;
    private static final Pattern PAGE_TITLE = Pattern.compile(
            "^(auction|orders).*?page\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE
    );

    private final Map<String, PageSnapshot> pages = new LinkedHashMap<>();
    private List<MarketOpportunity> opportunities = List.of();
    private Screen lastScreen;
    private String lastPageKey;
    private int ticksUntilScan;

    void tick(Minecraft client) {
        if (--ticksUntilScan > 0) {
            return;
        }
        ticksUntilScan = SCAN_INTERVAL_TICKS;

        if (client.player == null || client.screen == null) {
            resetActiveScreen();
            return;
        }

        Matcher titleMatcher = PAGE_TITLE.matcher(client.screen.getTitle().getString().trim());
        if (!titleMatcher.find()) {
            resetActiveScreen();
            return;
        }

        MarketSide side = titleMatcher.group(1).equalsIgnoreCase("auction")
                ? MarketSide.AUCTION
                : MarketSide.ORDER;
        int pageNumber;
        try {
            pageNumber = Integer.parseInt(titleMatcher.group(2));
        } catch (NumberFormatException exception) {
            resetActiveScreen();
            return;
        }

        AbstractContainerMenu menu = client.player.containerMenu;
        int marketSlotCount = Math.max(0, menu.slots.size() - PLAYER_INVENTORY_SLOTS);
        if (marketSlotCount == 0) {
            resetActiveScreen();
            return;
        }

        String pageKey = side.name() + ":" + pageNumber;
        int fingerprint = fingerprint(menu, marketSlotCount);
        boolean newScreen = client.screen != lastScreen;
        PageSnapshot oldSnapshot = pages.get(pageKey);
        boolean contentChanged = oldSnapshot == null || oldSnapshot.fingerprint() != fingerprint;

        if (!pageKey.equals(lastPageKey) || newScreen || contentChanged) {
            capture(client, menu, side, pageNumber, marketSlotCount, fingerprint, pageKey);
        }

        lastScreen = client.screen;
        lastPageKey = pageKey;
    }

    List<MarketOpportunity> opportunities() {
        return opportunities;
    }

    int pageCount(MarketSide side) {
        int count = 0;
        for (PageSnapshot page : pages.values()) {
            if (page.side() == side) {
                count++;
            }
        }
        return count;
    }

    int freshPageCount(MarketSide side, long nowMillis) {
        int count = 0;
        for (PageSnapshot page : pages.values()) {
            if (page.side() == side
                    && nowMillis - page.capturedAtMillis() <= STALE_AFTER_MILLIS) {
                count++;
            }
        }
        return count;
    }

    private void capture(
            Minecraft client,
            AbstractContainerMenu menu,
            MarketSide side,
            int pageNumber,
            int marketSlotCount,
            int fingerprint,
            String pageKey
    ) {
        long now = System.currentTimeMillis();
        List<MarketListing> listings = new ArrayList<>();

        for (int index = 0; index < marketSlotCount; index++) {
            ItemStack stack = menu.slots.get(index).getItem();
            if (stack.isEmpty()) {
                continue;
            }

            List<Component> tooltip = stack.getTooltipLines(
                    Item.TooltipContext.create(client.player.level()),
                    client.player,
                    TooltipFlag.Default.NORMAL
            );
            PriceParser.ParsedMoney price = null;
            PriceParser.DeliveredProgress progress = null;
            List<String> itemDetails = new ArrayList<>();

            for (Component component : tooltip) {
                String line = component.getString().trim();
                PriceParser.ParsedMoney parsedPrice = PriceParser.parseMoney(line);
                if (parsedPrice != null) {
                    if (side == MarketSide.AUCTION && !parsedPrice.perItem() && price == null) {
                        price = parsedPrice;
                    } else if (side == MarketSide.ORDER && parsedPrice.perItem() && price == null) {
                        price = parsedPrice;
                    }
                }

                PriceParser.DeliveredProgress parsedProgress = PriceParser.parseDelivered(line);
                if (parsedProgress != null) {
                    progress = parsedProgress;
                }

                if (!isMarketplaceMetadata(line)) {
                    String normalized = line.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
                    if (!normalized.isEmpty()) {
                        itemDetails.add(normalized);
                    }
                }
            }

            if (price == null) {
                continue;
            }

            int quantity;
            int delivered = 0;
            int totalOrderQuantity = 0;
            if (side == MarketSide.AUCTION) {
                quantity = stack.getCount();
            } else {
                if (progress == null) {
                    continue;
                }
                delivered = progress.delivered();
                totalOrderQuantity = progress.total();
                quantity = progress.remaining();
            }
            if (quantity <= 0) {
                continue;
            }

            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String itemName = stack.getHoverName().getString();
            String identityKey = itemId + "|" + String.join("|", itemDetails);

            listings.add(new MarketListing(
                    side,
                    itemId,
                    itemName,
                    identityKey,
                    quantity,
                    price.displayed(),
                    price.lowerBound(),
                    price.upperBound(),
                    price.approximate(),
                    pageNumber,
                    index,
                    delivered,
                    totalOrderQuantity,
                    now
            ));
        }

        pages.remove(pageKey);
        pages.put(pageKey, new PageSnapshot(
                side,
                pageNumber,
                fingerprint,
                marketSlotCount,
                now,
                listings
        ));
        trimPages(side);
        opportunities = OpportunityFinder.rank(pages.values(), now);
    }

    private static boolean isMarketplaceMetadata(String line) {
        if (PriceParser.parseMoney(line) != null || PriceParser.parseDelivered(line) != null) {
            return true;
        }

        String normalized = line.toLowerCase(Locale.ROOT).trim();
        return normalized.startsWith("click to ")
                || normalized.startsWith("minecraft:")
                || normalized.matches(".*\\d+\\s+components?\\).*");
    }

    private static int fingerprint(AbstractContainerMenu menu, int marketSlotCount) {
        int hash = 1;
        for (int index = 0; index < marketSlotCount; index++) {
            ItemStack stack = menu.slots.get(index).getItem();
            hash = 31 * hash + System.identityHashCode(stack.getItem());
            hash = 31 * hash + stack.getCount();
            hash = 31 * hash + stack.getComponents().hashCode();
        }
        return hash;
    }

    private void trimPages(MarketSide side) {
        int sideCount = pageCount(side);
        if (sideCount <= MAX_PAGES_PER_SIDE) {
            return;
        }

        Iterator<Map.Entry<String, PageSnapshot>> iterator = pages.entrySet().iterator();
        while (iterator.hasNext() && sideCount > MAX_PAGES_PER_SIDE) {
            if (iterator.next().getValue().side() == side) {
                iterator.remove();
                sideCount--;
            }
        }
    }

    private void resetActiveScreen() {
        lastScreen = null;
        lastPageKey = null;
    }
}
