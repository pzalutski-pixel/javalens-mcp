package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tools that filter the matches of a search see every match: get_http_endpoints keeps the
 * handler methods among all references to a verb annotation, get_jpa_model the types among all
 * references to @Entity, and change_method_signature rewrites every call site among all
 * references to the method, however many there are. Each test adds 1001 matching declarations
 * or call sites to a copy of a fixture, written by the test, and asserts the whole payload
 * through the JSON-RPC envelope, which
 * {@link EnvelopeHarness#assertEnvelopeFidelity} also proves equal to execute().
 */
class SearchCompletenessTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("get_http_endpoints reports all 1001 @GetMapping handlers of BulkController besides framework-maven's five routes")
    void httpEndpoints_reportEveryHandler() throws Exception {
        Path root = helper.copyFixture("framework-maven");
        // BulkController.java: the class on line 4, then per handler i an annotation line and
        // the method name five lines apart, the first name on line 7.
        StringBuilder source = new StringBuilder("package com.fw.bulk;\n\n"
            + "import org.springframework.web.bind.annotation.GetMapping;\n\npublic class BulkController {\n\n");
        for (int i = 0; i <= 1000; i++) {
            source.append(String.format("    @GetMapping(\"/bulk/%04d\")\n    public String endpoint%04d() {\n"
                + "        return \"%04d\";\n    }\n\n", i, i, i));
        }
        source.append("}\n");
        write(root, "src/main/java/com/fw/bulk/BulkController.java", source.toString());
        EnvelopeHarness envelope = load(root);

        List<Map<String, Object>> endpoints = new ArrayList<>();
        endpoints.add(endpoint("POST", "/api/orders", "com.fw.OrderController#create()", "spring",
            "src/main/java/com/fw/OrderController.java", 17));
        endpoints.add(endpoint("GET", "/api/orders/{id}", "com.fw.OrderController#get(long)", "spring",
            "src/main/java/com/fw/OrderController.java", 12));
        for (int i = 0; i <= 1000; i++) {
            endpoints.add(endpoint("GET", String.format("/bulk/%04d", i),
                String.format("com.fw.bulk.BulkController#endpoint%04d()", i), "spring",
                "src/main/java/com/fw/bulk/BulkController.java", 7 + 5 * i));
        }
        endpoints.add(endpoint("POST", "/legacy", "com.fw.LegacyResource#submit()", "jaxrs",
            "src/main/java/com/fw/LegacyResource.java", 16));
        endpoints.add(endpoint("GET", "/legacy/ping", "com.fw.LegacyResource#ping()", "jaxrs",
            "src/main/java/com/fw/LegacyResource.java", 11));
        endpoints.add(endpoint("GET", "/status", "com.fw.StatusController#status()", "spring",
            "src/main/java/com/fw/StatusController.java", 9));

        ObjectNode args = mapper.createObjectNode();
        args.put("maxResults", 2000);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("endpointCount", 1006);
        data.put("endpoints", endpoints);
        assertEquals(json(success(data, 1006, 1006, List.of(
                "analyze_method on a handler for its callers and callees",
                "find_affected_tests to see which tests cover a handler"))),
            envelope.assertEnvelopeFidelity("get_http_endpoints", args));
    }

    @Test
    @DisplayName("get_jpa_model reports all 1001 @Entity types of com.fw.bulk besides framework-maven's two entities")
    void jpaModel_reportsEveryEntity() throws Exception {
        Path root = helper.copyFixture("framework-maven");
        // Item0000.java to Item1000.java: the class name on line 5 of each.
        for (int i = 0; i <= 1000; i++) {
            write(root, String.format("src/main/java/com/fw/bulk/Item%04d.java", i), String.format(
                "package com.fw.bulk;\n\nimport jakarta.persistence.Entity;\n\n@Entity\npublic class Item%04d {\n}\n", i));
        }
        EnvelopeHarness envelope = load(root);

        List<Map<String, Object>> entities = new ArrayList<>();
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", "com.fw.Customer");
        customer.put("table", "customers");
        customer.put("idField", "id");
        customer.put("relationships", List.of(Map.of(
            "field", "orders", "kind", "OneToMany", "target", "com.fw.Order", "mappedBy", "customer")));
        customer.put("filePath", "src/main/java/com/fw/Customer.java");
        customer.put("line", 11);
        entities.add(customer);
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("name", "com.fw.Order");
        order.put("idField", "id");
        order.put("relationships", List.of(Map.of(
            "field", "customer", "kind", "ManyToOne", "target", "com.fw.Customer")));
        order.put("filePath", "src/main/java/com/fw/Order.java");
        order.put("line", 7);
        entities.add(order);
        for (int i = 0; i <= 1000; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", String.format("com.fw.bulk.Item%04d", i));
            item.put("relationships", List.of());
            item.put("filePath", String.format("src/main/java/com/fw/bulk/Item%04d.java", i));
            item.put("line", 5);
            entities.add(item);
        }

        ObjectNode args = mapper.createObjectNode();
        args.put("maxResults", 2000);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entityCount", 1003);
        data.put("entities", entities);
        assertEquals(json(success(data, 1003, 1003, List.of(
                "analyze_type for full details of an entity",
                "find_references to see where an entity is used"))),
            envelope.assertEnvelopeFidelity("get_jpa_model", args));
    }

    @Test
    @DisplayName("change_method_signature rewrites all 1001 call sites of Unit.unit besides its declaration")
    void changeMethodSignature_rewritesEveryCallSite() throws Exception {
        Path root = helper.copyFixture("capped-searches");
        // Unit.java: unit declared on line 4, its return type at column 18.
        write(root, "src/main/java/org/example/capped/Unit.java", "package org.example.capped;\n\n"
            + "public class Unit {\n\n    public static int unit(int a) {\n        return a;\n    }\n}\n");
        // Calls00.java to Calls10.java: 91 calls each, one per line from line 6, each at column 17.
        for (int file = 0; file <= 10; file++) {
            StringBuilder calls = new StringBuilder(String.format(
                "package org.example.capped;\n\nclass Calls%02d {\n\n    int run() {\n        int total = 0;\n", file));
            for (int call = 0; call < 91; call++) {
                calls.append("        total += Unit.unit(1);\n");
            }
            calls.append("        return total;\n    }\n}\n");
            write(root, String.format("src/main/java/org/example/capped/Calls%02d.java", file), calls.toString());
        }
        EnvelopeHarness envelope = load(root);

        Map<String, Object> editsByFile = new LinkedHashMap<>();
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("type", "replace");
        declaration.put("startLine", 4);
        declaration.put("startColumn", 18);
        declaration.put("endLine", 4);
        declaration.put("endColumn", 33);
        declaration.put("oldSignature", "int unit(int a)");
        declaration.put("newSignature", "int measure(int a)");
        declaration.put("isDeclaration", true);
        editsByFile.put("src/main/java/org/example/capped/Unit.java", List.of(declaration));
        for (int file = 0; file <= 10; file++) {
            List<Map<String, Object>> edits = new ArrayList<>();
            for (int call = 0; call < 91; call++) {
                Map<String, Object> edit = new LinkedHashMap<>();
                edit.put("type", "replace");
                edit.put("startLine", 6 + call);
                edit.put("startColumn", 17);
                edit.put("endLine", 6 + call);
                edit.put("endColumn", 29);
                edit.put("oldText", "Unit.unit(1)");
                edit.put("newText", "Unit.measure(1)");
                edit.put("isDeclaration", false);
                edits.add(edit);
            }
            editsByFile.put(String.format("src/main/java/org/example/capped/Calls%02d.java", file), edits);
        }

        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve("src/main/java/org/example/capped/Unit.java").toString());
        args.put("line", 4);
        args.put("column", 22);
        args.put("newName", "measure");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("oldName", "unit");
        data.put("newName", "measure");
        data.put("oldReturnType", "int");
        data.put("newReturnType", "int");
        data.put("oldParameterCount", 1);
        data.put("newParameterCount", 1);
        data.put("newParameters", List.of(Map.of("name", "a", "type", "int")));
        data.put("totalEdits", 1002);
        data.put("filesAffected", 12);
        data.put("editsByFile", editsByFile);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("success", true);
        expected.put("data", data);
        expected.put("meta", Map.of(
            "totalCount", 1002,
            "returnedCount", 1002,
            "suggestedNextTools", List.of(
                "Apply the text edits to complete the signature change",
                "get_diagnostics to verify no errors")));

        // Raw offsets are left out of the comparison; every edit is pinned by line and column.
        JsonNode payload = envelope.assertEnvelopeFidelity("change_method_signature", args);
        assertEquals(json(expected), withoutOffsets(payload));
    }

    private EnvelopeHarness load(Path root) throws Exception {
        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(root);
        return new EnvelopeHarness(service);
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private JsonNode json(Object value) {
        return mapper.valueToTree(value);
    }

    /** {@code payload} with every startOffset and endOffset field removed. */
    private static JsonNode withoutOffsets(JsonNode payload) {
        JsonNode copy = payload.deepCopy();
        strip(copy);
        return copy;
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(List.of("startOffset", "endOffset"));
        }
        node.forEach(SearchCompletenessTest::strip);
    }

    private static Map<String, Object> endpoint(String httpMethod, String path, String handler, String framework,
                                                String filePath, int line) {
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("httpMethod", httpMethod);
        endpoint.put("path", path);
        endpoint.put("handler", handler);
        endpoint.put("framework", framework);
        endpoint.put("filePath", filePath);
        endpoint.put("line", line);
        return endpoint;
    }

    private static Map<String, Object> success(Map<String, Object> data, int total, int returned, List<String> nextTools) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", total);
        meta.put("returnedCount", returned);
        meta.put("truncated", false);
        meta.put("suggestedNextTools", nextTools);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("data", data);
        payload.put("meta", meta);
        return payload;
    }
}
