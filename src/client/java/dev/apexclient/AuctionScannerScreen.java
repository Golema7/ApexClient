package dev.apexclient;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

final class AuctionScannerScreen extends Screen {
    private static final int PANEL_WIDTH = 440;
    private static final int PANEL_HEIGHT = 278;
    private static final int ROWS_TO_SHOW = 4;
    private final MarketScanner scanner;
    private final AutoFlipBot autoFlip;

    AuctionScannerScreen(MarketScanner scanner, AutoFlipBot autoFlip) {
        super(Component.literal("ApexClient Auction Scanner"));
        this.scanner = scanner;
        this.autoFlip = autoFlip;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(220, this.width - 16));
        int panelHeight = Math.min(PANEL_HEIGHT, Math.max(210, this.height - 16));
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
                "AH-average opportunities | based on other fresh matching listings",
                textLeft,
                top + 25,
                0xFFB6C4CE,
                false
        );

        String botText = "Auto Flip: " + autoFlip.status();
        graphics.drawString(this.minecraft.font, trimToWidth(botText, contentWidth), textLeft, top + 45, 0xFFFFFFFF, false);
        String pagesText = "Captured pages: /ah " + scanner.freshPageCount(MarketSide.AUCTION, now)
                + "/" + scanner.pageCount(MarketSide.AUCTION)
                + " fresh | /orders " + scanner.freshPageCount(MarketSide.ORDER, now)
                + "/" + scanner.pageCount(MarketSide.ORDER) + " fresh";
        graphics.drawString(this.minecraft.font, trimToWidth(pagesText, contentWidth), textLeft, top + 58, 0xFFB6C4CE, false);

        int shown = 0;
        for (MarketOpportunity opportunity : scanner.opportunities()) {
            if (!opportunity.isFresh(now)) {
                continue;
            }
            if (shown >= ROWS_TO_SHOW || top + 82 + shown * 31 > top + panelHeight - 66) {
                break;
            }

            MarketListing purchase = opportunity.purchase();
            String name = purchase.itemName() + " x" + purchase.quantity();
            graphics.drawString(this.minecraft.font, trimToWidth(name, contentWidth), textLeft, top + 77 + shown * 31, 0xFFFFFFFF, false);

            String buyPrice = formatMoney(purchase.displayedPrice());
            String proceeds = formatMoney(opportunity.conservativeProceeds());
            String profit = "+" + formatMoney(opportunity.conservativeProfit());
            String details = (purchase.approximatePrice() ? "~" : "")
                    + "Buy " + buyPrice + " | AH avg >= " + proceeds
                    + " | gross " + profit + " | " + formatPercent(opportunity.returnPercent());
            graphics.drawString(this.minecraft.font, trimToWidth(details, contentWidth), textLeft, top + 89 + shown * 31, 0xFF68D391, false);
            shown++;
        }

        if (shown == 0) {
            String message = scanner.pageCount(MarketSide.AUCTION) == 0
                    ? "Open /ah to capture auction listings."
                    : "No positive AH-average match in the captured pages.";
            graphics.drawString(this.minecraft.font, trimToWidth(message, contentWidth), textLeft, top + 86, 0xFFFFFFFF, false);
            graphics.drawString(
                    this.minecraft.font,
                    "Auto Flip stops if it cannot verify the page, Yes button, or inventory result.",
                    textLeft,
                    top + 102,
                    0xFFB6C4CE,
                    false
            );
        }

        int buttonX = left + 14;
        int buttonY = top + panelHeight - 48;
        int buttonWidth = panelWidth - 28;
        graphics.fill(buttonX, buttonY, buttonX + buttonWidth, buttonY + 22,
                autoFlip.isActive() ? 0xFF713F3F : 0xFF246B59);
        graphics.renderOutline(buttonX, buttonY, buttonWidth, 22, 0xFF8EDCC7);
        String button = autoFlip.buttonText();
        int buttonTextX = buttonX + (buttonWidth - this.minecraft.font.width(button)) / 2;
        graphics.drawString(this.minecraft.font, button, buttonTextX, buttonY + 7, 0xFFFFFFFF, false);

        graphics.drawString(
                this.minecraft.font,
                "Per-stack cap $500,000 | positive /ah-average margin | fees unknown",
                textLeft,
                top + panelHeight - 17,
                0xFF8F9DA8,
                false
        );
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(220, this.width - 16));
        int panelHeight = Math.min(PANEL_HEIGHT, Math.max(210, this.height - 16));
        int left = (this.width - panelWidth) / 2;
        int top = (this.height - panelHeight) / 2;
        int buttonX = left + 14;
        int buttonY = top + panelHeight - 48;
        int buttonWidth = panelWidth - 28;
        if (event.button() == 0
                && mouseX >= buttonX && mouseX <= buttonX + buttonWidth
                && mouseY >= buttonY && mouseY <= buttonY + 22) {
            autoFlip.toggle(this.minecraft);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
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
