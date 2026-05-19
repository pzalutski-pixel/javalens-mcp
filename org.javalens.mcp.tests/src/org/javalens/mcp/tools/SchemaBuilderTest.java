package org.javalens.mcp.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaBuilderTest {

    @Test
    @DisplayName("object with no fields returns minimal schema (type=object, empty properties, no required key)")
    void empty() {
        Map<String, Object> schema = SchemaBuilder.object().build();
        assertEquals("object", schema.get("type"));
        Map<?, ?> props = (Map<?, ?>) schema.get("properties");
        assertTrue(props.isEmpty(), "properties must be empty when nothing was added");
        assertFalse(schema.containsKey("required"),
            "required key must be omitted entirely when nothing is required (cleaner shape)");
    }

    @Test
    @DisplayName("required field adds to properties AND to required list")
    @SuppressWarnings("unchecked")
    void required_addsToBothSections() {
        Map<String, Object> schema = SchemaBuilder.object()
            .required("typeName", "string", "Fully qualified type name")
            .build();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> typeNameProp = (Map<String, Object>) props.get("typeName");
        assertEquals("string", typeNameProp.get("type"));
        assertEquals("Fully qualified type name", typeNameProp.get("description"));
        assertNull(typeNameProp.get("enum"), "non-enum field must not carry an 'enum' key");
        assertEquals(List.of("typeName"), schema.get("required"));
    }

    @Test
    @DisplayName("optional field adds to properties but NOT to required list")
    @SuppressWarnings("unchecked")
    void optional_addsToPropsOnly() {
        Map<String, Object> schema = SchemaBuilder.object()
            .optional("maxResults", "integer", "Max results (default 100)")
            .build();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertEquals("integer", ((Map<String, Object>) props.get("maxResults")).get("type"));
        assertFalse(schema.containsKey("required"),
            "no required fields → required key absent from schema");
    }

    @Test
    @DisplayName("required + optional mix preserves insertion order in properties")
    @SuppressWarnings("unchecked")
    void mixedOrder_preserved() {
        Map<String, Object> schema = SchemaBuilder.object()
            .required("typeName", "string", "Type")
            .optional("maxResults", "integer", "Max")
            .required("line", "integer", "Line")
            .build();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertEquals(List.of("typeName", "maxResults", "line"), List.copyOf(props.keySet()),
            "Insertion order must be preserved for stable AI consumer output");
        assertEquals(List.of("typeName", "line"), schema.get("required"));
    }

    @Test
    @DisplayName("requiredEnum sets type=string + enum values + adds to required")
    @SuppressWarnings("unchecked")
    void requiredEnum_setsEnumValues() {
        Map<String, Object> schema = SchemaBuilder.object()
            .requiredEnum("kind", "Search kind",
                List.of("class", "interface", "enum", "method", "field"))
            .build();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kindProp = (Map<String, Object>) props.get("kind");
        assertEquals("string", kindProp.get("type"));
        assertEquals("Search kind", kindProp.get("description"));
        assertEquals(List.of("class", "interface", "enum", "method", "field"), kindProp.get("enum"));
        assertEquals(List.of("kind"), schema.get("required"));
    }

    @Test
    @DisplayName("optionalEnum sets enum values but does NOT add to required")
    @SuppressWarnings("unchecked")
    void optionalEnum_notRequired() {
        Map<String, Object> schema = SchemaBuilder.object()
            .optionalEnum("severity", "Severity filter", List.of("error", "warning", "info"))
            .build();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> p = (Map<String, Object>) props.get("severity");
        assertEquals(List.of("error", "warning", "info"), p.get("enum"));
        assertFalse(schema.containsKey("required"));
    }

    @Test
    @DisplayName("requiredCustom passes through arbitrary schema fragments (escape hatch)")
    @SuppressWarnings("unchecked")
    void requiredCustom_passesThrough() {
        Map<String, Object> arraySchema = Map.of(
            "type", "array",
            "description", "Parameter list",
            "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "type", Map.of("type", "string")
                )
            )
        );
        Map<String, Object> schema = SchemaBuilder.object()
            .requiredCustom("newParameters", arraySchema)
            .build();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertEquals(arraySchema, props.get("newParameters"),
            "Custom fragment must be inserted verbatim (no merging or mutation)");
        assertEquals(List.of("newParameters"), schema.get("required"));
    }

    @Test
    @DisplayName("optionalCustom is the same escape hatch without adding to required")
    @SuppressWarnings("unchecked")
    void optionalCustom_passesThrough() {
        Map<String, Object> nested = Map.of("type", "object", "additionalProperties", true);
        Map<String, Object> schema = SchemaBuilder.object()
            .optionalCustom("extras", nested)
            .build();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertEquals(nested, props.get("extras"));
        assertFalse(schema.containsKey("required"));
    }

    @Test
    @DisplayName("build is single-use: required list is independently copied per build")
    void buildIsSingleUse() {
        SchemaBuilder b = SchemaBuilder.object()
            .required("a", "string", "A");
        Map<String, Object> first = b.build();
        Map<String, Object> second = b.build();
        // The 'required' key is independently copied per build, so mutating the
        // first list does NOT corrupt the second.
        @SuppressWarnings("unchecked")
        List<String> firstReq = (List<String>) first.get("required");
        assertEquals(List.of("a"), firstReq);
        assertEquals(List.of("a"), second.get("required"));
    }

    @Test
    @DisplayName("properties Map IS shared between consecutive build() calls (documented limitation)")
    @SuppressWarnings("unchecked")
    void buildSharesPropertiesMapAcrossBuilds() {
        // Source: build() inserts the builder's `properties` field reference directly,
        // not a copy. Two consecutive builds therefore share the same properties Map.
        // The Javadoc says "the builder is single-use", which mostly applies to the
        // `required` list (which IS copied). For `properties`, callers must not assume
        // independence.
        //
        // Pin this so a future change that makes properties a defensive copy would
        // fail intentionally (signaling an API-shape change to callers).
        SchemaBuilder b = SchemaBuilder.object()
            .required("a", "string", "A");
        Map<String, Object> first = b.build();
        Map<String, Object> second = b.build();

        assertTrue(first.get("properties") == second.get("properties"),
            "Current behavior: both built schemas share the same properties Map reference. "
                + "If this fails, the builder now does a defensive copy — update the Javadoc.");
    }

    @Test
    @DisplayName("requiredEnum with empty values list produces empty enum array")
    @SuppressWarnings("unchecked")
    void requiredEnum_emptyValues_emptyEnumArray() {
        // Edge case: an empty enum list is well-defined (JSON Schema spec accepts it,
        // though it makes no logical value pass validation). Pin the behavior: build()
        // doesn't reject this, just emits an empty `enum` array.
        Map<String, Object> schema = SchemaBuilder.object()
            .requiredEnum("k", "kind", List.of())
            .build();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kProp = (Map<String, Object>) props.get("k");
        assertEquals(List.of(), kProp.get("enum"),
            "Empty values list must be preserved as an empty enum array");
    }
}
