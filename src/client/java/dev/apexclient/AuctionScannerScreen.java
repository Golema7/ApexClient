package dev.apexclient;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

final class AuctionScannerScreen extends Screen {
    private static final int PANEL_WIDTH = 440;
    private static final int PANEL_HEIGHT = 248;
    private static final int ROWS_TO_SHOW = 5;
    private final MarketScanner scanner;

    AuctionScannerScreen(MarketScanner scanner) {
        super(Component.literal("ApexClient Auction Scanner"));
        this.scanner = scanner;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(220, this.width - 16));
        int panelHeight = Math.min(PANEL_HEIGHT, Math.max(180, this.height - 16));
        int left = (this.width - panelWidth) / 2;
        int top = (this.height - panelHeight) / 2;
        long now = System.currentTimeMillis();

        graphics.fill(0, 0, this.width, this.height, 0xC0101218);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFF191D26);
        graphics.renderOutline(left, top, panelWidth, panelHeight, 0xFF5BC0A5);
        graphics.fill(left + 1, top + 35, left + panelWidth - 1, top + 36, 0xFF39444F);

        int textLeft = left + 14;
        int contentWidth = panelWidth - 28;
        graphics.drawString(this.minecraft.font, "ApexClient Auction Scanner", textLeft, top + 12, 0xFFFFFFFF, false);
        graphics.drawString(
                this.minecraft.font,
                "Read-only estimates | no market actions are performed",
                textLeft,
                top + 25,
                0xFFB6C4CE,
                false
        );

        String pagesText = "Visible pages: /ah " + scanner.freshPageCount(MarketSide.AUCTION, now)
                + "/" + scanner.pageCount(MarketSide.AUCTION)
                + " fresh | /orders " + scanner.freshPageCount(MarketSide.ORDER, now)
                + "/" + scanner.pageCount(MarketSide.ORDER) + " fresh";
        graphics.drawString(this.minecraft.font, trimToWidth(pagesText, contentWidth), textLeft, top + 45, 0xFFB6C4CE, false);

        int shown = 0;
        for (MarketOpportunity opportunity : scanner.opportunities()) {
            if (!opportunity.isFresh(now)) {
                continue;
            }
            if (shown >= ROWS_TO_SHOW || top + 68 + shown * 31 > top + panelHeight - 30) {
                break;
            }

            MarketListing purchase = opportunity.purchase();
            String name = purchase.itemName() + " x" + purchase.quantity();
            graphics.drawString(this.minecraft.font, trimToWidth(name, contentWidth), textLeft, top + 62 + shown * 31, 0xFFFFFFFF, false);

            String buyPrice = formatMoney(purchase.displayedPrice());
            String proceeds = formatMoney(opportunity.conservativeProceeds());
            String profit = "+" + formatMoney(opportunity.conservativeProfit());
            String details = (purchase.approximatePrice() ? "~" : "")
                    + "Buy " + buyPrice + " | sell >= " + proceeds
                    + " | gross " + profit + " | " + formatPercent(opportunity.returnPercent());
            graphics.drawString(this.minecraft.font, trimToWidth(details, contentWidth), textLeft, top + 74 + shown * 31, 0xFF68D391, false);
            shown++;
        }

        if (shown == 0) {
            String message;
            if (scanner.pageCount(MarketSide.AUCTION) == 0 || scanner.pageCount(MarketSide.ORDER) == 0) {
                message = "Open /ah and /orders and visit pages to capture them.";
            } else {
                message = "No positive fresh matches in the captured pages.";
            }
            graphics.drawString(this.minecraft.font, trimToWidth(message, contentWidth), textLeft, top + 76, 0xFFFFFFFF, false);
            graphics.drawString(
                    this.minecraft.font,
                    "Rounded prices use conservative bounds. Results are before fees.",
                    textLeft,
                    top + 92,
                    0xFFB6C4CE,
                    false
            );
        }

        graphics.drawString(
                this.minecraft.font,
                "M opens this screen in-game | Esc closes | captures older than 2 min are stale",
                textLeft,
                top + panelHeight - 17,
                0xFF8F9DA8,
                false
        );
    }

    private String trimToWidth(String text, int maximumWidth) {
        if (this.minecraft.font.width(text) <= maximumWidth) {
            return text;
        }

        String shortened = text;
        while (shortened.length() > 3 && this.minecraft.font.width(shortened + "...") > maximumWidth) {
            shortened = shortened.substring(0, shortened.length() - 1);
        }
        return shortened + "...";
    }

    private static String formatMoney(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        return "$" + format.format(amount);
    }

    private static String formatPercent(BigDecimal percent) {
        return percent.stripTrailingZeros().toPlainString() + "%";
    }
}
