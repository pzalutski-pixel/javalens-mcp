package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetHttpEndpointsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins get_http_endpoints (issue #24) against framework-maven: effective
 * routes composed from class-level prefixes and method-level paths, for both
 * Spring verb-shortcut annotations and JAX-RS, sorted by path then verb.
 */
class GetHttpEndpointsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetHttpEndpointsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("framework-maven");
        tool = new GetHttpEndpointsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> endpoints(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("endpoints");
    }

    @Test
    @DisplayName("assembles the exact route table across Spring and JAX-RS")
    void exactRouteTable() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        assertEquals(5, getData(r).get("endpointCount"));
        List<Map<String, Object>> endpoints = endpoints(r);
        assertEquals(5, endpoints.size(), () -> "endpoints: " + endpoints);

        assertEndpoint(endpoints.get(0), "POST", "/api/orders",
            "com.fw.OrderController#create()", "spring",
            "src/main/java/com/fw/OrderController.java", 17);
        assertEndpoint(endpoints.get(1), "GET", "/api/orders/{id}",
            "com.fw.OrderController#get(long)", "spring",
            "src/main/java/com/fw/OrderController.java", 12);
        assertEndpoint(endpoints.get(2), "POST", "/legacy",
            "com.fw.LegacyResource#submit()", "jaxrs",
            "src/main/java/com/fw/LegacyResource.java", 16);
        assertEndpoint(endpoints.get(3), "GET", "/legacy/ping",
            "com.fw.LegacyResource#ping()", "jaxrs",
            "src/main/java/com/fw/LegacyResource.java", 11);
        assertEndpoint(endpoints.get(4), "GET", "/status",
            "com.fw.StatusController#status()", "spring",
            "src/main/java/com/fw/StatusController.java", 9);
    }

    private void assertEndpoint(Map<String, Object> endpoint, String httpMethod, String path,
                                String handler, String framework, String filePath, int line) {
        assertEquals(httpMethod, endpoint.get("httpMethod"), () -> "httpMethod of " + endpoint);
        assertEquals(path, endpoint.get("path"), () -> "path of " + endpoint);
        assertEquals(handler, endpoint.get("handler"), () -> "handler of " + endpoint);
        assertEquals(framework, endpoint.get("framework"), () -> "framework of " + endpoint);
        assertEquals(filePath, endpoint.get("filePath"), () -> "filePath of " + endpoint);
        assertEquals(line, endpoint.get("line"), () -> "line of " + endpoint);
    }

    @Test
    @DisplayName("maxResults truncates the sorted table with true totals")
    void maxResultsTruncates() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 2);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertEquals(2, endpoints(r).size());
        assertEquals("/api/orders", endpoints(r).get(0).get("path"));
        assertEquals(5, r.getMeta().getTotalCount());
        assertEquals(Boolean.TRUE, r.getMeta().getTruncated());
    }

    @Test
    @DisplayName("negative maxResults is rejected as INVALID_PARAMETER")
    void negativeMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", -1);
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    @Test
    @DisplayName("without a loaded project returns PROJECT_NOT_LOADED")
    void projectNotLoaded() {
        GetHttpEndpointsTool unloaded = new GetHttpEndpointsTool(() -> null);
        ToolResponse r = unloaded.execute(objectMapper.createObjectNode());
        assertFalse(r.isSuccess());
        assertEquals("PROJECT_NOT_LOADED", r.getError().getCode());
    }
}
