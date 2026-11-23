package dev.apexclient;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PriceParser {
    private static final Pattern MONEY = Pattern.compile(
            "^\\s*\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([KMBT]?)\\s*(each)?\\s*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DELIVERED = Pattern.compile(
            "^\\s*([0-9][0-9,]*)\\s*[:/]\\s*([0-9][0-9,]*)\\s+delivered\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private PriceParser() {
    }

    static ParsedMoney parseMoney(String line) {
        Matcher matcher = MONEY.matcher(line.trim());
        if (!matcher.matches()) {
            return null;
        }

        try {
            BigDecimal number = new BigDecimal(matcher.group(1).replace(",", ""));
            String suffix = matcher.group(2).toUpperCase(Locale.ROOT);
            int exponent = switch (suffix) {
                case "K" -> 3;
                case "M" -> 6;
                case "B" -> 9;
                case "T" -> 12;
                default -> 0;
            };
            BigDecimal multiplier = BigDecimal.TEN.pow(exponent);
            BigDecimal displayed = number.multiply(multiplier);
            boolean approximate = exponent > 0;

            BigDecimal lower = displayed;
            BigDecimal upper = displayed;
            if (approximate) {
                int shownDecimals = Math.max(0, number.stripTrailingZeros().scale());
                BigDecimal displayStep = multiplier.movePointLeft(shownDecimals);
                lower = displayed.subtract(displayStep).max(BigDecimal.ZERO);
                upper = displayed.add(displayStep);
            }

            return new ParsedMoney(displayed, lower, upper, approximate, matcher.group(3) != null);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static DeliveredProgress parseDelivered(String line) {
        Matcher matcher = DELIVERED.matcher(line.trim());
        if (!matcher.matches()) {
            return null;
        }

        try {
            int delivered = new BigInteger(matcher.group(1).replace(",", ""))
                    .min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
            int total = new BigInteger(matcher.group(2).replace(",", ""))
                    .min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
            return new DeliveredProgress(delivered, total);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    record ParsedMoney(
            BigDecimal displayed,
            BigDecimal lowerBound,
            BigDecimal upperBound,
            boolean approximate,
            boolean perItem
    ) {
    }

    record DeliveredProgress(int delivered, int total) {
        int remaining() {
            return Math.max(0, total - delivered);
        }
    }
}
