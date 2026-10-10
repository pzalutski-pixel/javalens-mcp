package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetHttpEndpointsTool;
import org.javalens.mcp.tools.GetJpaModelTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * Every declaration a framework annotation marks is in the answer, whatever its shape: a handler
 * whose signature names a type that does not resolve is a route, and an enum, a record or an
 * annotation type annotated {@code @Entity} is an entity. The compiler gives a method whose
 * signature names a missing type no binding unless bindings are recovered
 * (DefaultBindingResolver.getMethodOrLambdaBinding :232-234), so a declaration is found by its
 * name's position, never by its binding. A verb-annotated element of an annotation type is no
 * route: no framework registers an annotation type.
 *
 * <p>Each test adds files to a copy of framework-maven, package com.fw. BrokenController.java:
 * {@code @RestController @RequestMapping("/shapes")} (lines 6, 7) on the class (line 8), with
 * {@code @GetMapping("/missing") public Missing find(long id)} (lines 10, 11; Missing does not
 * resolve) and {@code @GetMapping("/plain") public String plain()} (lines 15, 16).
 * RouteMarker.java: the annotation type RouteMarker (line 4) whose element {@code value()}
 * (line 7) carries {@code @GetMapping("/marker")} (line 6). Status.java: {@code @Entity} (line 5)
 * on the enum Status (line 6), constants ACTIVE and CLOSED, and {@code @Id private long code;}
 * (lines 10, 11). Point.java: {@code @Entity} (line 4) on the record {@code Point(int x, int y)}
 * (line 5). Tagged.java: {@code @Entity} (line 4) on the annotation type Tagged (line 5).
 * Lines are zero-based.
 */
class FrameworkDeclarationsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String BROKEN_CONTROLLER = """
        package com.fw;

        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.web.bind.annotation.RequestMapping;
        import org.springframework.web.bind.annotation.RestController;

        @RestController
        @RequestMapping("/shapes")
        public class BrokenController {

            @GetMapping("/missing")
            public Missing find(long id) {
                return null;
            }

            @GetMapping("/plain")
            public String plain() {
                return "plain";
            }
        }
        """;

    private static final String ROUTE_MARKER = """
        package com.fw;

        import org.springframework.web.bind.annotation.GetMapping;

        public @interface RouteMarker {

            @GetMapping("/marker")
            String value() default "";
        }
        """;

    private static final String STATUS = """
        package com.fw;

        import jakarta.persistence.Entity;
        import jakarta.persistence.Id;

        @Entity
        public enum Status {
            ACTIVE,
            CLOSED;

            @Id
            private long code;
        }
        """;

    private static final String POINT = """
        package com.fw;

        import jakarta.persistence.Entity;

        @Entity
        public record Point(int x, int y) {
        }
        """;

    private static final String TAGGED = """
        package com.fw;

        import jakarta.persistence.Entity;

        @Entity
        public @interface Tagged {
        }
        """;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("get_http_endpoints lists BrokenController.find, whose return type does not resolve, beside plain, and no route for RouteMarker's element")
    void handlerWithUnresolvedSignature() throws Exception {
        JdtServiceImpl service = load(Map.of("BrokenController.java", BROKEN_CONTROLLER,
            "RouteMarker.java", ROUTE_MARKER));
        // Sorted by path, then verb: framework-maven's five routes and BrokenController's two.
        Map<String, Object> data = map(
            "endpointCount", 7,
            "endpoints", List.of(
                endpoint("POST", "/api/orders", "com.fw.OrderController#create()", "spring", "OrderController", 17),
                endpoint("GET", "/api/orders/{id}", "com.fw.OrderController#get(long)", "spring", "OrderController", 12),
                endpoint("POST", "/legacy", "com.fw.LegacyResource#submit()", "jaxrs", "LegacyResource", 16),
                endpoint("GET", "/legacy/ping", "com.fw.LegacyResource#ping()", "jaxrs", "LegacyResource", 11),
                endpoint("GET", "/shapes/missing", "com.fw.BrokenController#find(long)", "spring", "BrokenController", 11),
                endpoint("GET", "/shapes/plain", "com.fw.BrokenController#plain()", "spring", "BrokenController", 16),
                endpoint("GET", "/status", "com.fw.StatusController#status()", "spring", "StatusController", 9)));
        Map<String, Object> meta = map(
            "totalCount", 7,
            "returnedCount", 7,
            "truncated", false,
            "suggestedNextTools", List.of(
                "analyze_method on a handler for its callers and callees",
                "find_affected_tests to see which tests cover a handler"));
        assertPayload(mapper, new GetHttpEndpointsTool(() -> service), new EnvelopeHarness(service),
            mapper.createObjectNode(), success(data, meta));
    }

    @Test
    @DisplayName("get_jpa_model lists the enum Status, the record Point and the annotation type Tagged annotated @Entity beside framework-maven's Customer and Order")
    void entitiesOfEveryTypeKind() throws Exception {
        JdtServiceImpl service = load(Map.of("Status.java", STATUS, "Point.java", POINT, "Tagged.java", TAGGED));
        // In name order.
        Map<String, Object> data = map(
            "entityCount", 5,
            "entities", List.of(
                map("name", "com.fw.Customer", "table", "customers", "idField", "id",
                    "relationships", List.of(map("field", "orders", "kind", "OneToMany", "target", "com.fw.Order",
                        "mappedBy", "customer")),
                    "filePath", "src/main/java/com/fw/Customer.java", "line", 11),
                map("name", "com.fw.Order", "idField", "id",
                    "relationships", List.of(map("field", "customer", "kind", "ManyToOne", "target", "com.fw.Customer")),
                    "filePath", "src/main/java/com/fw/Order.java", "line", 7),
                map("name", "com.fw.Point", "relationships", List.of(),
                    "filePath", "src/main/java/com/fw/Point.java", "line", 5),
                map("name", "com.fw.Status", "idField", "code", "relationships", List.of(),
                    "filePath", "src/main/java/com/fw/Status.java", "line", 6),
                map("name", "com.fw.Tagged", "relationships", List.of(),
                    "filePath", "src/main/java/com/fw/Tagged.java", "line", 5)));
        Map<String, Object> meta = map(
            "totalCount", 5,
            "returnedCount", 5,
            "truncated", false,
            "suggestedNextTools", List.of(
                "analyze_type for full details of an entity",
                "find_references to see where an entity is used"));
        assertPayload(mapper, new GetJpaModelTool(() -> service), new EnvelopeHarness(service),
            mapper.createObjectNode(), success(data, meta));
    }

    /** A copy of framework-maven with {@code files} added to package com.fw, loaded. */
    private JdtServiceImpl load(Map<String, String> files) throws Exception {
        Path root = helper.copyFixture("framework-maven");
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(root.resolve("src/main/java/com/fw").resolve(file.getKey()), file.getValue(),
                StandardCharsets.UTF_8);
        }
        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(root);
        return service;
    }

    private static Map<String, Object> endpoint(String verb, String path, String handler, String framework,
                                                String type, int line) {
        return map(
            "httpMethod", verb,
            "path", path,
            "handler", handler,
            "framework", framework,
            "filePath", "src/main/java/com/fw/" + type + ".java",
            "line", line);
    }
}
