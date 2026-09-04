package de.lino.cloud.api.utility;

public final class UnitParser {

    /** UnitParser labels {@link #resolveBytesToUnit(long)} scales through, smallest to largest. */
    private static final String[] BYTE_UNITS = {"B", "KB", "MB", "GB", "TB"};

    /** UnitParser labels {@link #resolveMilliSecondsToUnit(long)} scales through, smallest to largest. */
    private static final String[] TIME_UNITS = {"ms", "s", "min", "h", "d"};

    /** Divisor applied to move from each {@link #TIME_UNITS} entry to the next larger one. */
    private static final int[] TIME_UNIT_DIVISORS = {1000, 60, 60, 24};

    private UnitParser() {}

    public static String parsePercentage(final long dottedValue) {
        return String.format("%.2f %s", (dottedValue * 100.0), "%").replace(',', '.');
    }

    /**
     * Formats {@code bytes} in its largest whole unit (e.g. {@code 2048} -> {@code "2.00KB"}).
     *
     * @param bytes the byte count to format
     * @return the formatted string
     */
    public static String parseByteUnit(final long bytes) {

        double value = bytes;
        int unit = 0;

        while (value >= 1024 && unit < BYTE_UNITS.length - 1) {
            value /= 1024;
            unit++;
        }

        return (unit == 0 ? String.valueOf((long) value) : String.format("%.2f", value).replace(",", ".")) + " " + BYTE_UNITS[unit];

    }

    /**
     * Formats {@code milliseconds} in its largest whole unit (e.g. {@code 90_000} -&gt;
     * {@code "1.50 min"}).
     *
     * @param milliseconds the duration to format
     * @return the formatted string
     */
    public static String parseTimeUnit(final long milliseconds) {

        double value = milliseconds;
        int unit = 0;

        while (unit < TIME_UNIT_DIVISORS.length && value >= TIME_UNIT_DIVISORS[unit]) {
            value /= TIME_UNIT_DIVISORS[unit];
            unit++;
        }

        return (unit == 0 ? String.valueOf((long) value) : String.format("%.2f", value).replace(",", ".")) + " " + TIME_UNITS[unit];

    }

}
