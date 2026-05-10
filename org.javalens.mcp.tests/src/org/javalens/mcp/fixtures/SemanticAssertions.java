package org.javalens.mcp.fixtures;

import org.javalens.mcp.models.ToolResponse;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Semantic-grade assertion helpers shared across tool tests. Use these to assert
 * exact expected content — not response shape — against deterministic fixtures.
 */
public final class SemanticAssertions {

    private SemanticAssertions() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> data(ToolResponse response) {
        return (Map<String, Object>) response.getData();
    }

    public static Map<String, Object> assertSuccessData(ToolResponse response) {
        assertTrue(response.isSuccess(),
            () -> "Expected success but got error: " +
                (response.getError() != null ? response.getError().getCode() + " " +
                    response.getError().getMessage() : "null"));
        return data(response);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getList(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value == null) {
            return List.of();
        }
        return (List<Map<String, Object>>) value;
    }

    public static <T> Set<T> fieldSet(List<Map<String, Object>> items, String field) {
        return items.stream()
            .map(m -> {
                @SuppressWarnings("unchecked")
                T v = (T) m.get(field);
                return v;
            })
            .collect(Collectors.toSet());
    }

    public static void assertFieldSet(List<Map<String, Object>> items, String field, Set<?> expected) {
        Set<?> actual = fieldSet(items, field);
        assertEquals(expected, actual,
            () -> "Field '" + field + "' values did not match expected set");
    }

    public static void assertQualifiedNames(List<Map<String, Object>> items, Set<String> expected) {
        assertFieldSet(items, "qualifiedName", expected);
    }

    public static void assertCount(Map<String, Object> data, String countField, int expected) {
        Object actual = data.get(countField);
        assertEquals(expected, actual,
            () -> "Field '" + countField + "' did not match expected count");
    }
}
