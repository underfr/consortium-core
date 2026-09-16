package org.consortium.core.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void formatsCentsWithGroupingAndTwoDecimals() {
        assertEquals("1,234.56 CC", Money.format(123456));
        assertEquals("0.00 CC", Money.format(0));
        assertEquals("0.05 CC", Money.format(5));
        assertEquals("-12.00", Money.formatPlain(-1200));
        assertEquals("+87.68", Money.formatSigned(8768));
        assertEquals("10,000,000,000.00 CC", Money.format(Money.MAX_BALANCE_CENTS));
    }

    @Test
    void parsesUpToTwoDecimals() {
        assertEquals(1250, Money.parseCredits("12.50"));
        assertEquals(1200, Money.parseCredits("12"));
        assertEquals(1210, Money.parseCredits("12.1"));
        assertEquals(0, Money.parseCredits("0"));
        assertEquals(5, Money.parseCredits(" 0.05 "));
    }

    @Test
    void rejectsBadAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.parseCredits("1.234"));
        assertThrows(IllegalArgumentException.class, () -> Money.parseCredits("-5"));
        assertThrows(IllegalArgumentException.class, () -> Money.parseCredits("1,000"));
        assertThrows(IllegalArgumentException.class, () -> Money.parseCredits("abc"));
        assertThrows(IllegalArgumentException.class, () -> Money.parseCredits(""));
        assertThrows(IllegalArgumentException.class, () -> Money.parseCredits(null));
        assertThrows(IllegalArgumentException.class, () -> Money.parseCredits("1e5"));
        assertThrows(IllegalArgumentException.class, () -> Money.parseCredits("9999999999999999999"));
    }

    @Test
    void additionOverflowsLoudly() {
        assertThrows(ArithmeticException.class, () -> Money.add(Long.MAX_VALUE, 1));
        assertEquals(3, Money.add(1, 2));
    }

    @Test
    void floorsDoublesToCentsAndNeverOverpays() {
        assertEquals(178533, Money.floorToCents(1785.339999));
        assertEquals(0, Money.floorToCents(0.004));
        assertEquals(0, Money.floorToCents(-3));
        assertEquals(0, Money.floorToCents(Double.NaN));
        assertEquals(Money.MAX_BALANCE_CENTS, Money.floorToCents(1e30));
        // 0.29 * 100 is 28.999999999999996 in double arithmetic: the epsilon keeps it at 29 cents.
        assertEquals(29, Money.floorToCents(0.29));
    }
}
