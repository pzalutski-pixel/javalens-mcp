package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetHttpEndpointsTool;
import org.javalens.mcp.tools.GetJpaModelTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #24 acceptance: frameworks absent from the classpath produce empty
 * results, not errors. simple-maven has no JPA, Spring-web, or JAX-RS types.
 */
class FrameworkAbsentTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("get_jpa_model without JPA on the classpath returns an empty model")
    void jpaModel_empty() {
        ToolResponse r = new GetJpaModelTool(() -> service).execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());
        assertEquals(0, getData(r).get("entityCount"));
        assertEquals(List.of(), getData(r).get("entities"));
    }

    @Test
    @DisplayName("get_http_endpoints without web frameworks returns an empty table")
    void httpEndpoints_empty() {
        ToolResponse r = new GetHttpEndpointsTool(() -> service).execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());
        assertEquals(0, getData(r).get("endpointCount"));
        assertEquals(List.of(), getData(r).get("endpoints"));
    }
}
