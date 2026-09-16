package org.consortium.core.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Consortium Credits arithmetic. Every amount is a {@code long} number of cents (1 credit = 100 cents).
 *
 * <p>Doubles only ever serve the price curve; a ledger line is always rounded DOWN to cents, and every
 * addition goes through {@link Math#addExact(long, long)} so an overflow surfaces as an exception instead
 * of a wrapped balance. This class has no Minecraft dependency so the unit tests cover it directly.
 */
public final class Money {
    /** Cents in one credit. */
    public static final long CENTS_PER_CREDIT = 100L;
    /** Upper bound of {@code /credits set} and of any single balance: 10^12 cents (ten billion credits). */
    public static final long MAX_BALANCE_CENTS = 1_000_000_000_000L;
    /** Default currency symbol; the common config can override it at display time. */
    public static final String DEFAULT_SYMBOL = "CC";

    private static final DecimalFormat FORMAT;

    static {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');
        FORMAT = new DecimalFormat("#,##0.00", symbols);
        FORMAT.setRoundingMode(RoundingMode.DOWN);
    }

    private Money() {
    }

    /** {@code 123456} cents becomes {@code "1,234.56"} (no symbol). Negative amounts keep their sign. */
    public static String formatPlain(long cents) {
        synchronized (FORMAT) {
            return FORMAT.format(BigDecimal.valueOf(cents, 2));
        }
    }

    /** {@code 123456} cents becomes {@code "1,234.56 CC"}. */
    public static String format(long cents) {
        return format(cents, DEFAULT_SYMBOL);
    }

    /** Same as {@link #format(long)} with a custom currency symbol. */
    public static String format(long cents, String symbol) {
        return formatPlain(cents) + " " + symbol;
    }

    /** Signed variant for deltas: {@code "+87.68"} or {@code "-12.00"}. */
    public static String formatSigned(long cents) {
        return cents >= 0 ? "+" + formatPlain(cents) : formatPlain(cents);
    }

    /**
     * Parses a credit amount typed by an admin or a script: digits with an optional dot and at most two decimals.
     * Group separators, signs, exponents and blanks are rejected.
     *
     * @throws IllegalArgumentException when the text is not a valid amount or does not fit in a long
     */
    public static long parseCredits(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Amount is missing");
        }
        String s = text.trim();
        if (s.isEmpty() || !s.matches("\\d{1,15}(\\.\\d{1,2})?")) {
            throw new IllegalArgumentException("Invalid amount '" + text + "': use digits with at most two decimals, e.g. 12.50");
        }
        BigDecimal value = new BigDecimal(s).movePointRight(2);
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amount '" + text + "' is out of range");
        }
    }

    /** {@link Math#addExact(long, long)}; the caller turns the {@link ArithmeticException} into INVALID_AMOUNT. */
    public static long add(long a, long b) {
        return Math.addExact(a, b);
    }

    /** {@link Math#subtractExact(long, long)}. */
    public static long subtract(long a, long b) {
        return Math.subtractExact(a, b);
    }

    /**
     * Rounds a credit amount computed in double precision DOWN to cents. Non-finite or negative inputs give 0,
     * values beyond {@link #MAX_BALANCE_CENTS} are capped there (the caller alerts on such a line anyway).
     */
    public static long floorToCents(double credits) {
        if (Double.isNaN(credits) || credits <= 0) {
            return 0L;
        }
        double cents = Math.floor(credits * CENTS_PER_CREDIT + 1e-9);
        if (cents >= (double) MAX_BALANCE_CENTS) {
            return MAX_BALANCE_CENTS;
        }
        return (long) cents;
    }

    /** Cents as a double number of credits, for the curve and the reports. */
    public static double toCredits(long cents) {
        return cents / (double) CENTS_PER_CREDIT;
    }
}
