package org.javalens.core;

import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins how JAVALENS_TIMEOUT_SECONDS sets the time limit on each tool call: unset, no limit; a
 * whole number from 5 to 300, that limit; a whole number outside the range, clamped with a
 * warning; anything else, no limit with a warning.
 */
class TimeoutSettingTest {

    private static final String REMEDIATION =
        "Set JAVALENS_TIMEOUT_SECONDS to a whole number of seconds from 5 to 300, or unset it for no limit, "
            + "then restart JavaLens.";

    private static LoadWarning warning(String message) {
        return new LoadWarning(LoadWarning.TIMEOUT_SECONDS_INVALID, message, REMEDIATION);
    }

    @Test
    @DisplayName("each value sets exactly its limit and warning")
    void eachValue_setsItsLimitAndWarning() {
        Map<String, TimeoutSetting> expected = new LinkedHashMap<>();
        expected.put("5", new TimeoutSetting(5, null));
        expected.put("30", new TimeoutSetting(30, null));
        expected.put("300", new TimeoutSetting(300, null));
        expected.put(" 30\t", new TimeoutSetting(30, null));
        expected.put("+45", new TimeoutSetting(45, null));
        expected.put("4", new TimeoutSetting(5, warning(
            "JAVALENS_TIMEOUT_SECONDS is \"4\", below 5: each tool call is limited to 5 seconds")));
        expected.put("0", new TimeoutSetting(5, warning(
            "JAVALENS_TIMEOUT_SECONDS is \"0\", below 5: each tool call is limited to 5 seconds")));
        expected.put("-30", new TimeoutSetting(5, warning(
            "JAVALENS_TIMEOUT_SECONDS is \"-30\", below 5: each tool call is limited to 5 seconds")));
        expected.put("301", new TimeoutSetting(300, warning(
            "JAVALENS_TIMEOUT_SECONDS is \"301\", above 300: each tool call is limited to 300 seconds")));
        expected.put("99999999999999999999", new TimeoutSetting(300, warning(
            "JAVALENS_TIMEOUT_SECONDS is \"99999999999999999999\", above 300: each tool call is limited to 300 seconds")));
        expected.put("abc", new TimeoutSetting(null, warning(
            "JAVALENS_TIMEOUT_SECONDS is \"abc\", which is not a whole number of seconds: tool calls have no time limit")));
        expected.put("30.5", new TimeoutSetting(null, warning(
            "JAVALENS_TIMEOUT_SECONDS is \"30.5\", which is not a whole number of seconds: tool calls have no time limit")));
        expected.put("", new TimeoutSetting(null, warning(
            "JAVALENS_TIMEOUT_SECONDS is \"\", which is not a whole number of seconds: tool calls have no time limit")));
        expected.put("30s", new TimeoutSetting(null, warning(
            "JAVALENS_TIMEOUT_SECONDS is \"30s\", which is not a whole number of seconds: tool calls have no time limit")));

        Map<String, TimeoutSetting> actual = new LinkedHashMap<>();
        for (String value : expected.keySet()) {
            actual.put(value, TimeoutSetting.of(value));
        }
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("unset sets no limit and raises no warning")
    void unset_noLimitNoWarning() {
        assertEquals(new TimeoutSetting(null, null), TimeoutSetting.of(null));
    }

    @Test
    @DisplayName("the build environment leaves the variable unset, so the setting it holds is no limit")
    void environment_ofTheBuild_isUnset() {
        assertEquals(new TimeoutSetting(null, null), TimeoutSetting.fromEnvironment());
    }
}
