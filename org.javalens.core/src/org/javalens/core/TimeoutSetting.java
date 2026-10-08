package org.javalens.core;

import org.javalens.core.project.model.LoadWarning;

import java.math.BigInteger;

/**
 * The time limit on each tool call that the {@value #VARIABLE} environment variable sets.
 *
 * <p>Unset, the variable sets no limit. A whole number of seconds from {@value #MIN_SECONDS} to
 * {@value #MAX_SECONDS}, read with surrounding whitespace removed, is the limit. A whole number
 * outside that range is clamped into it, and any other value sets no limit; either raises
 * {@link LoadWarning#TIMEOUT_SECONDS_INVALID}, which states the value given and its effect.
 *
 * @param seconds the limit in seconds, or null for no limit
 * @param warning the warning the value raises, or null when the variable is unset or in range
 */
public record TimeoutSetting(Integer seconds, LoadWarning warning) {

    public static final String VARIABLE = "JAVALENS_TIMEOUT_SECONDS";
    public static final int MIN_SECONDS = 5;
    public static final int MAX_SECONDS = 300;

    /** The setting {@value #VARIABLE} holds in this process's environment. */
    public static TimeoutSetting fromEnvironment() {
        return of(System.getenv(VARIABLE));
    }

    /** The setting {@value #VARIABLE} holds when its value is {@code value}; null when it is unset. */
    public static TimeoutSetting of(String value) {
        if (value == null) {
            return new TimeoutSetting(null, null);
        }
        BigInteger number;
        try {
            number = new BigInteger(value.strip());
        } catch (NumberFormatException e) {
            return new TimeoutSetting(null, warning(value,
                "which is not a whole number of seconds: tool calls have no time limit"));
        }
        if (number.compareTo(BigInteger.valueOf(MIN_SECONDS)) < 0) {
            return new TimeoutSetting(MIN_SECONDS, warning(value,
                "below " + MIN_SECONDS + ": each tool call is limited to " + MIN_SECONDS + " seconds"));
        }
        if (number.compareTo(BigInteger.valueOf(MAX_SECONDS)) > 0) {
            return new TimeoutSetting(MAX_SECONDS, warning(value,
                "above " + MAX_SECONDS + ": each tool call is limited to " + MAX_SECONDS + " seconds"));
        }
        return new TimeoutSetting(number.intValueExact(), null);
    }

    private static LoadWarning warning(String value, String effect) {
        return new LoadWarning(LoadWarning.TIMEOUT_SECONDS_INVALID,
            VARIABLE + " is \"" + value + "\", " + effect,
            "Set " + VARIABLE + " to a whole number of seconds from " + MIN_SECONDS + " to " + MAX_SECONDS
                + ", or unset it for no limit, then restart JavaLens.");
    }
}
