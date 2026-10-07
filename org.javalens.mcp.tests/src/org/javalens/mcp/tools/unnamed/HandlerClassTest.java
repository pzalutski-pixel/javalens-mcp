package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetHttpEndpointsTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.javalens.mcp.tools.unnamed.UnnamedSemantics.PACKAGE;
import static org.javalens.mcp.tools.unnamed.UnnamedSemantics.ROUTES;

/**
 * get_http_endpoints lists a handler only where a framework can register it: in a named
 * top-level or static member class, record or enum, a static member class of an inner class
 * included, its path prefixed by that type's own {@code @Path}. A verb-annotated method of an
 * anonymous class, a local class or a non-static inner class (which needs an enclosing instance
 * or method context) is no route and is left out. Where a handler is declared never stops the
 * listing: every other handler of the same verb is listed. Routes.java in unnamed-semantics
 * ({@link UnnamedSemantics}).
 */
class HandlerClassTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("get_http_endpoints lists the handlers of Routes, of its static class Archive, its record Ledger, its enum Level and the static class Deep of its inner class Holder, and none of an anonymous, local or inner class")
    void handlersOfRegistrableClassesOnly() throws Exception {
        JdtServiceImpl service = helper.loadProject(UnnamedSemantics.FIXTURE);
        // Sorted by path, then verb.
        Map<String, Object> data = map(
            "endpointCount", 8,
            "endpoints", List.of(
                endpoint("GET", "/archive/all", "Routes$Archive#all()", 94),
                endpoint("POST", "/archive/store", "Routes$Archive#store()", 100),
                endpoint("GET", "/deep/leaf", "Routes$Holder$Deep#leaf()", 111),
                endpoint("GET", "/ledger/entry", "Routes$Ledger#entry()", 74),
                endpoint("POST", "/level/current", "Routes$Level#current()", 85),
                endpoint("POST", "/routes/add", "Routes#add()", 45),
                endpoint("GET", "/routes/count", "Routes#count()", 39),
                endpoint("GET", "/routes/list", "Routes#list()", 33)));
        Map<String, Object> meta = map(
            "totalCount", 8,
            "returnedCount", 8,
            "truncated", false,
            "suggestedNextTools", List.of(
                "analyze_method on a handler for its callers and callees",
                "find_affected_tests to see which tests cover a handler"));
        assertPayload(mapper, new GetHttpEndpointsTool(() -> service), new EnvelopeHarness(service),
            mapper.createObjectNode(), success(data, meta));
    }

    private static Map<String, Object> endpoint(String verb, String path, String handler, int line) {
        return map(
            "httpMethod", verb,
            "path", path,
            "handler", PACKAGE + handler,
            "framework", "jaxrs",
            "filePath", ROUTES,
            "line", line);
    }
}
