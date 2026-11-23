package dev.apexclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AutoFlipBot {
    private static final BigDecimal MAX_BUY_TOTAL = new BigDecimal("500000");
    private static final int MIN_PRICE_COMPARABLES = 2;
    private static final int MAX_SCAN_PAGES = 30;
    private static final Pattern AUCTION_PAGE = Pattern.compile(
            "^auction.*?page\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE
    );

    private final MarketScanner scanner;
    private final Set<Integer> visitedPages = new HashSet<>();
    private State state = State.IDLE;
    private boolean active;
    private boolean stopAfterTrade;
    private String status = "Off";
    private int stateTicks;
    private int previousPage;
    private int targetPage;
    private int expectedQuantity;
    private long pageOpenedAt;
    private String targetItemId;
    private String lastSkippedOpportunityKey = "";
    private MarketListing candidate;
    private BigDecimal targetSellPrice = BigDecimal.ZERO;
    private Screen purchaseScreen;

    AutoFlipBot(MarketScanner scanner) {
        this.scanner = scanner;
    }

    boolean isActive() {
        return active;
    }

    String status() {
        return status;
    }

    String buttonText() {
        if (!active) {
            return "Start Auto Flip";
        }
        return stopAfterTrade ? "Finish current sale, then stop" : "Stop Auto Flip";
    }

    void toggle(Minecraft client) {
        if (active) {
            requestStop(client);
            return;
        }
        if (client.player == null) {
            return;
        }

        active = true;
        stopAfterTrade = false;
        state = State.OPEN_AH;
        stateTicks = 0;
        candidate = null;
        lastSkippedOpportunityKey = "";
        visitedPages.clear();
        scanner.clearAuctionPages();
        status = "Starting: scanning /ah pages...";
        if (client.screen instanceof AuctionScannerScreen) {
            client.setScreen(null);
        }
        notify(client, "Auto Flip started. Max buy is $500,000 per stack; qualifying buys must beat the comparable /ah average.");
    }

    void requestStop(Minecraft client) {
        if (!active) {
            return;
        }
        if (state == State.WAIT_PURCHASE || state == State.PREPARE_SELL
                || state == State.WAIT_EQUIP || state == State.WAIT_SELL) {
            stopAfterTrade = true;
            status = "Finishing the current sale, then stopping.";
            if (client.screen instanceof AuctionScannerScreen) {
                client.setScreen(null);
            }
            notify(client, status);
            return;
        }

        active = false;
        stopAfterTrade = false;
        state = State.IDLE;
        status = "Stopped";
        notify(client, status);
    }

    void tick(Minecraft client) {
        if (!active || client.player == null) {
            return;
        }
        if (client.screen instanceof AuctionScannerScreen) {
            return;
        }

        switch (state) {
            case IDLE -> active = false;
            case OPEN_AH -> openAuctionHouse(client);
            case WAIT_AH -> waitForAuctionHouse(client);
            case SCAN_PAGE -> scanCurrentPage(client);
            case AFTER_CAPTURE -> advancePage(client);
            case WAIT_NEXT_PAGE -> waitForNextPage(client);
            case REOPEN_AH -> reopenAuctionHouse(client);
            case SEEK_TARGET_PAGE -> seekTargetPage(client);
            case WAIT_TARGET_PAGE -> waitForTargetPage(client);
            case BUY_CANDIDATE -> buyCandidate(client);
            case WAIT_CONFIRM -> confirmPurchase(client);
            case WAIT_PURCHASE -> waitForPurchase(client);
            case PREPARE_SELL -> prepareSell(client);
            case WAIT_EQUIP -> waitForEquippedItem(client);
            case WAIT_SELL -> waitForSale(client);
            case COOLDOWN -> cooldown(client);
        }
    }

    private void openAuctionHouse(Minecraft client) {
        if (client.screen != null) {
            if (auctionPage(client.screen) > 0) {
                pageOpenedAt = System.currentTimeMillis();
                state = State.SCAN_PAGE;
                stateTicks = 0;
                visitedPages.clear();
                return;
            }
            stopWithError(client, "Close the current menu before Auto Flip can scan /ah.");
            return;
        }
        client.player.connection.sendCommand("ah");
        pageOpenedAt = System.currentTimeMillis();
        state = State.WAIT_AH;
        stateTicks = 0;
    }

    private void waitForAuctionHouse(Minecraft client) {
        int page = auctionPage(client.screen);
        if (page > 0) {
            pageOpenedAt = System.currentTimeMillis();
            visitedPages.clear();
            state = State.SCAN_PAGE;
            stateTicks = 0;
        } else if (++stateTicks > 80) {
            stopWithError(client, "The /ah menu did not open. Auto Flip stopped.");
        }
    }

    private void scanCurrentPage(Minecraft client) {
        int page = auctionPage(client.screen);
        if (page <= 0) {
            if (client.screen == null && ++stateTicks < 80) {
                return;
            }
            stopWithError(client, "Lost the /ah page while scanning. Auto Flip stopped.");
            return;
        }
        if (visitedPages.contains(page)) {
            finishScan(client);
            return;
        }
        if (visitedPages.size() >= MAX_SCAN_PAGES) {
            finishScan(client);
            return;
        }

        PageSnapshot snapshot = scanner.page(MarketSide.AUCTION, page);
        if (snapshot == null || snapshot.capturedAtMillis() < pageOpenedAt - 1500) {
            if (++stateTicks > 80) {
                stopWithError(client, "Could not capture auction page " + page + ".");
            }
            return;
        }

        visitedPages.add(page);
        previousPage = page;
        state = State.AFTER_CAPTURE;
        stateTicks = 0;
        status = "Captured /ah page " + page + "; moving through listings...";
    }

    private void advancePage(Minecraft client) {
        if (++stateTicks < 12) {
            return;
        }
        if (visitedPages.size() >= MAX_SCAN_PAGES) {
            finishScan(client);
            return;
        }

        int nextSlot = nextPageSlot(client);
        if (nextSlot < 0) {
            finishScan(client);
            return;
        }
        if (!clickSlot(client, nextSlot)) {
            return;
        }
        state = State.WAIT_NEXT_PAGE;
        stateTicks = 0;
    }

    private void waitForNextPage(Minecraft client) {
        int page = auctionPage(client.screen);
        if (page > 0 && page != previousPage) {
            if (visitedPages.contains(page)) {
                finishScan(client);
            } else {
                pageOpenedAt = System.currentTimeMillis();
                state = State.SCAN_PAGE;
                stateTicks = 0;
            }
            return;
        }
        if (++stateTicks > 40) {
            finishScan(client);
        }
    }

    private void finishScan(Minecraft client) {
        long now = System.currentTimeMillis();
        candidate = bestCandidate(now);
        if (candidate == null) {
            MarketOpportunity skipped = scanner.opportunities().stream()
                    .filter(opportunity -> opportunity.isFresh(now))
                    .filter(opportunity -> opportunity.purchase().priceUpperBound().compareTo(MAX_BUY_TOTAL) > 0
                            || inventoryCountByItemId(opportunity.purchase().itemId()) > 0)
                    .findFirst()
                    .orElse(null);
            if (skipped != null) {
                MarketListing purchase = skipped.purchase();
                String opportunityKey = purchase.identityKey() + ":" + purchase.page() + ":"
                        + purchase.slot() + ":" + purchase.priceUpperBound().toPlainString();
                String reason = purchase.priceUpperBound().compareTo(MAX_BUY_TOTAL) > 0
                        ? "it exceeds the $500,000 per-stack cap"
                        : "that item is already in your inventory";
                status = "Profitable /ah-average match skipped because " + reason + ".";
                if (!opportunityKey.equals(lastSkippedOpportunityKey)) {
                    lastSkippedOpportunityKey = opportunityKey;
                    notify(client, "Found an /ah-average opportunity for " + purchase.itemName()
                            + " x" + purchase.quantity() + ", but skipped it because " + reason + ".");
                }
            } else {
                status = "No qualifying /ah-average flip found; scanning again shortly.";
            }
            state = State.COOLDOWN;
            stateTicks = 0;
            return;
        }

        targetPage = candidate.page();
        targetItemId = candidate.itemId();
        expectedQuantity = candidate.quantity();
        targetSellPrice = averageTotal(candidate, System.currentTimeMillis());
        BigDecimal estimatedProfit = targetSellPrice.subtract(candidate.priceUpperBound());
        status = "Profitable /ah-average flip found: " + candidate.itemName() + " x" + expectedQuantity
                + " | buy $" + money(candidate.priceUpperBound())
                + " | target $" + money(targetSellPrice)
                + " | estimated gross +$" + money(estimatedProfit)
                + "; verifying the listing.";
        notifyOpportunity(client, status);
        client.setScreen(null);
        state = State.REOPEN_AH;
        stateTicks = 0;
    }

    private void reopenAuctionHouse(Minecraft client) {
        if (client.screen != null) {
            client.setScreen(null);
            return;
        }
        if (++stateTicks < 5) {
            return;
        }
        client.player.connection.sendCommand("ah");
        pageOpenedAt = System.currentTimeMillis();
        state = State.SEEK_TARGET_PAGE;
        stateTicks = 0;
    }

    private void seekTargetPage(Minecraft client) {
        int page = auctionPage(client.screen);
        if (page <= 0) {
            if (++stateTicks > 80) {
                stopWithError(client, "Could not reopen /ah to verify the candidate.");
            }
            return;
        }
        if (page == targetPage) {
            PageSnapshot snapshot = scanner.page(MarketSide.AUCTION, page);
            if (snapshot != null && snapshot.capturedAtMillis() >= pageOpenedAt - 1500) {
                state = State.BUY_CANDIDATE;
                stateTicks = 0;
            } else if (++stateTicks > 80) {
                stopWithError(client, "Could not refresh the candidate listing.");
            }
            return;
        }
        if (page > targetPage) {
            stopWithError(client, "The /ah page order changed; no purchase was made.");
            return;
        }
        int nextSlot = nextPageSlot(client);
        if (nextSlot < 0) {
            stopWithError(client, "Could not find the next-page button; no purchase was made.");
            return;
        }
        previousPage = page;
        if (!clickSlot(client, nextSlot)) {
            return;
        }
        state = State.WAIT_TARGET_PAGE;
        stateTicks = 0;
    }

    private void waitForTargetPage(Minecraft client) {
        int page = auctionPage(client.screen);
        if (page == targetPage) {
            pageOpenedAt = System.currentTimeMillis();
            state = State.SEEK_TARGET_PAGE;
            stateTicks = 0;
        } else if (++stateTicks > 40) {
            stopWithError(client, "Could not navigate back to the candidate page.");
        }
    }

    private void buyCandidate(Minecraft client) {
        PageSnapshot snapshot = scanner.page(MarketSide.AUCTION, targetPage);
        if (snapshot == null || snapshot.capturedAtMillis() < pageOpenedAt - 1500) {
            state = State.SEEK_TARGET_PAGE;
            stateTicks = 0;
            return;
        }
        MarketListing live = snapshot.listings().stream()
                .filter(listing -> listing.slot() == candidate.slot())
                .findFirst()
                .orElse(null);
        if (live == null || !live.itemId().equals(candidate.itemId())
                || !live.identityKey().equals(candidate.identityKey())
                || live.quantity() != candidate.quantity()
                || live.priceUpperBound().compareTo(MAX_BUY_TOTAL) > 0
                || inventoryCount(client, live.itemId()) != 0
                || !hasEmptyInventorySlot(client)) {
            stopWithError(client, "The listing changed or inventory is not ready; no purchase was made.");
            return;
        }

        candidate = live;
        expectedQuantity = live.quantity();
        targetItemId = live.itemId();
        targetSellPrice = averageTotal(live, System.currentTimeMillis());
        if (targetSellPrice.compareTo(live.priceUpperBound()) <= 0) {
            stopWithError(client, "The verified price no longer clears the profit threshold.");
            return;
        }

        purchaseScreen = client.screen;
        if (!clickSlot(client, live.slot())) {
            return;
        }
        status = "Waiting for the listing's Yes confirmation...";
        state = State.WAIT_CONFIRM;
        stateTicks = 0;
    }

    private void confirmPurchase(Minecraft client) {
        if (client.screen == purchaseScreen) {
            if (++stateTicks > 40) {
                stopWithError(client, "The buy confirmation screen did not appear.");
            }
            return;
        }
        if (auctionPage(client.screen) > 0) {
            stopWithError(client, "The confirmation screen was not recognized; no Yes click was sent.");
            return;
        }
        int yesSlot = yesButtonSlot(client);
        if (yesSlot < 0) {
            if (++stateTicks > 40) {
                stopWithError(client, "Could not identify the Yes button; no confirmation was sent.");
            }
            return;
        }
        if (!clickSlot(client, yesSlot)) {
            return;
        }
        status = "Purchase confirmed; waiting for the item...";
        state = State.WAIT_PURCHASE;
        stateTicks = 0;
    }

    private void waitForPurchase(Minecraft client) {
        int count = inventoryCount(client, targetItemId);
        if (count == expectedQuantity) {
            state = State.PREPARE_SELL;
            stateTicks = 0;
            status = "Purchase received; preparing the stack for listing.";
        } else if (count > expectedQuantity) {
            stopWithError(client, "The received amount did not match the listing; no sale command was sent.");
        } else if (++stateTicks > 100) {
            stopWithError(client, "The purchased stack did not arrive in inventory.");
        }
    }

    private void prepareSell(Minecraft client) {
        if (client.screen != null) {
            client.setScreen(null);
            stateTicks = 0;
            return;
        }
        if (++stateTicks < 4) {
            return;
        }
        int inventorySlot = inventoryStackSlot(client, targetItemId, expectedQuantity);
        if (inventorySlot < 0) {
            stopWithError(client, "Could not isolate the purchased stack in inventory.");
            return;
        }

        Inventory inventory = client.player.getInventory();
        if (inventorySlot < 9) {
            inventory.setSelectedSlot(inventorySlot);
        } else {
            AbstractContainerMenu menu = client.player.containerMenu;
            if (menu.containerId != 0) {
                stopWithError(client, "Could not safely select the purchased stack.");
                return;
            }
            client.setScreen(new InventoryScreen(client.player));
            client.gameMode.handleInventoryMouseClick(
                    menu.containerId,
                    inventorySlot,
                    inventory.getSelectedSlot(),
                    ClickType.SWAP,
                    client.player
            );
        }
        state = State.WAIT_EQUIP;
        stateTicks = 0;
    }

    private void waitForEquippedItem(Minecraft client) {
        ItemStack held = client.player.getMainHandItem();
        if (itemId(held).equals(targetItemId) && held.getCount() == expectedQuantity) {
            if (client.screen != null) {
                client.setScreen(null);
            }
            String price = targetSellPrice.setScale(0, RoundingMode.FLOOR).toPlainString();
            client.player.connection.sendCommand("ah sell " + price);
            status = "Sent /ah sell " + price + "; verifying the listing...";
            state = State.WAIT_SELL;
            stateTicks = 0;
        } else if (++stateTicks > 40) {
            stopWithError(client, "Could not put the purchased stack in your main hand.");
        }
    }

    private void waitForSale(Minecraft client) {
        int count = inventoryCount(client, targetItemId);
        if (count == 0) {
            status = "Stack listed at the captured AH average.";
            notify(client, status);
            if (stopAfterTrade) {
                active = false;
                state = State.IDLE;
                status = "Trade complete; stopped.";
            } else {
                state = State.COOLDOWN;
                stateTicks = 0;
            }
        } else if (count < expectedQuantity) {
            stopWithError(client, "The server accepted only part of the stack; Auto Flip stopped.");
        } else if (++stateTicks > 80) {
            stopWithError(client, "The stack remains in inventory; the sell command was not confirmed.");
        }
    }

    private void cooldown(Minecraft client) {
        if (++stateTicks < 120) {
            return;
        }
        if (client.screen != null) {
            client.setScreen(null);
        }
        scanner.clearAuctionPages();
        visitedPages.clear();
        candidate = null;
        state = State.OPEN_AH;
        stateTicks = 0;
        status = "Rescanning /ah...";
    }

    private MarketListing bestCandidate(long now) {
        return scanner.opportunities().stream()
                .filter(opportunity -> opportunity.isFresh(now))
                .map(MarketOpportunity::purchase)
                .filter(listing -> listing.priceUpperBound().compareTo(MAX_BUY_TOTAL) <= 0)
                .filter(listing -> inventoryCountByItemId(listing.itemId()) == 0)
                .max(Comparator.comparing(listing ->
                        averageTotal(listing, now).subtract(listing.priceUpperBound())))
                .orElse(null);
    }

    private BigDecimal averageTotal(MarketListing listing, long now) {
        List<BigDecimal> comparableUnitPrices = new ArrayList<>();
        for (MarketListing other : scanner.freshListings(MarketSide.AUCTION, now)) {
            if (other.identityKey().equals(listing.identityKey())
                    && other.quantity() > 0
                    && (other.page() != listing.page() || other.slot() != listing.slot())) {
                comparableUnitPrices.add(other.priceLowerBound()
                        .divide(BigDecimal.valueOf(other.quantity()), 8, RoundingMode.DOWN));
            }
        }
        return averageTotal(listing, comparableUnitPrices);
    }

    private static BigDecimal averageTotal(MarketListing listing, List<BigDecimal> unitPrices) {
        if (unitPrices == null || unitPrices.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : unitPrices) {
            sum = sum.add(value);
        }
        BigDecimal averageUnit = sum.divide(BigDecimal.valueOf(unitPrices.size()), 8, RoundingMode.DOWN);
        return averageUnit.multiply(BigDecimal.valueOf(listing.quantity())).setScale(0, RoundingMode.DOWN);
    }

    private int nextPageSlot(Minecraft client) {
        if (client.player == null) {
            return -1;
        }
        AbstractContainerMenu menu = client.player.containerMenu;
        int marketSlots = Math.max(0, menu.slots.size() - 36);
        for (int slot = 0; slot < marketSlots; slot++) {
            ItemStack stack = menu.slots.get(slot).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            for (Component line : tooltip(client, stack)) {
                String text = line.getString().trim().toLowerCase(Locale.ROOT);
                if (text.contains("next page") || text.equals("next")) {
                    return slot;
                }
            }
        }
        if (marketSlots > 0 && menu.slots.get(marketSlots - 1).getItem().is(Items.ARROW)) {
            return marketSlots - 1;
        }
        return -1;
    }

    private int yesButtonSlot(Minecraft client) {
        AbstractContainerMenu menu = client.player.containerMenu;
        int marketSlots = Math.max(0, menu.slots.size() - 36);
        for (int slot = 0; slot < marketSlots; slot++) {
            ItemStack stack = menu.slots.get(slot).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            for (Component line : tooltip(client, stack)) {
                String text = line.getString().trim().toLowerCase(Locale.ROOT);
                if (text.equals("yes") || text.startsWith("yes ") || text.equals("confirm")) {
                    return slot;
                }
            }
            if (stack.getHoverName().getString().trim().equalsIgnoreCase("yes")) {
                return slot;
            }
        }
        return -1;
    }

    private List<Component> tooltip(Minecraft client, ItemStack stack) {
        return stack.getTooltipLines(
                Item.TooltipContext.of(client.player.level()),
                client.player,
                TooltipFlag.Default.NORMAL
        );
    }

    private boolean clickSlot(Minecraft client, int slot) {
        AbstractContainerMenu menu = client.player.containerMenu;
        if (!menu.getCarried().isEmpty()) {
            stopWithError(client, "Empty the cursor stack before Auto Flip can click a menu.");
            return false;
        }
        client.gameMode.handleInventoryMouseClick(
                menu.containerId,
                slot,
                0,
                ClickType.PICKUP,
                client.player
        );
        return true;
    }

    private int auctionPage(Screen screen) {
        if (screen == null) {
            return -1;
        }
        Matcher matcher = AUCTION_PAGE.matcher(screen.getTitle().getString().trim());
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private int inventoryCount(Minecraft client, String itemId) {
        Inventory inventory = client.player.getInventory();
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (itemId(stack).equals(itemId)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int inventoryCountByItemId(String itemId) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }
        return inventoryCount(client, itemId);
    }

    private boolean hasEmptyInventorySlot(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private int inventoryStackSlot(Minecraft client, String itemId, int count) {
        Inventory inventory = client.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (itemId(stack).equals(itemId) && stack.getCount() == count) {
                return i;
            }
        }
        return -1;
    }

    private String itemId(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private void stopWithError(Minecraft client, String message) {
        active = false;
        stopAfterTrade = false;
        state = State.IDLE;
        status = message;
        notify(client, message);
    }

    private void notify(Minecraft client, String message) {
        if (client.gui != null) {
            client.gui.getChat().addMessage(Component.literal("[ApexClient] " + message));
        }
    }

    private void notifyOpportunity(Minecraft client, String message) {
        notify(client, message);
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.0F));
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.FLOOR).toPlainString();
    }

    private enum State {
        IDLE,
        OPEN_AH,
        WAIT_AH,
        SCAN_PAGE,
        AFTER_CAPTURE,
        WAIT_NEXT_PAGE,
        REOPEN_AH,
        SEEK_TARGET_PAGE,
        WAIT_TARGET_PAGE,
        BUY_CANDIDATE,
        WAIT_CONFIRM,
        WAIT_PURCHASE,
        PREPARE_SELL,
        WAIT_EQUIP,
        WAIT_SELL,
        COOLDOWN
    }
}
