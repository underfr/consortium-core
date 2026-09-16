package org.consortium.core.economy;

import java.util.Locale;

/** Family units (count x weight per stack): computed in double, rounded to 3 decimals whenever stored or shown. */
public final class Units {
    private Units() {
    }

    /** Rounds half up to 3 decimals. */
    public static double round(double units) {
        if (Double.isNaN(units) || Double.isInfinite(units)) {
            return 0;
        }
        return Math.round(units * 1000.0) / 1000.0;
    }

    /** {@code 4321} or {@code 4,321.5}: grouped integer part, up to 3 decimals, trailing zeros trimmed. */
    public static String format(double units) {
        double r = round(units);
        if (r == Math.rint(r)) {
            return String.format(Locale.ROOT, "%,d", (long) r);
        }
        String s = String.format(Locale.ROOT, "%,.3f", r);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
