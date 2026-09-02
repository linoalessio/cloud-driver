package de.lino.cloud.api.utility;

public final class UnitParser {

    private UnitParser() {}

    public static String parsePercentage(final long dottedValue) {
        return String.format("%.2f %s", (dottedValue * 100.0), "%").replace(',', '.');
    }

}
