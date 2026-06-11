package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetJpaModelTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins get_jpa_model (issue #24) against framework-maven: the assembled
 * entity/relationship map with table names, id fields, relationship kinds,
 * resolved targets (including through collection type arguments), and
 * mappedBy sides - plus maxResults boundaries.
 */
class GetJpaModelToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetJpaModelTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("framework-maven");
        tool = new GetJpaModelTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> entities(ToolResponse r) {
        return (List<Map<String, Object>>) getData(r).get("entities");
    }

    @Test
    @DisplayName("assembles the exact entity model with relationships in both directions")
    @SuppressWarnings("unchecked")
    void exactEntityModel() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        assertEquals(2, getData(r).get("entityCount"));
        List<Map<String, Object>> entities = entities(r);
        assertEquals(2, entities.size());

        Map<String, Object> customer = entities.get(0);
        assertEquals("com.fw.Customer", customer.get("name"));
        assertEquals("customers", customer.get("table"));
        assertEquals("id", customer.get("idField"));
        assertEquals("src/main/java/com/fw/Customer.java", customer.get("filePath"));
        assertEquals(11, customer.get("line"));

        List<Map<String, Object>> customerRels = (List<Map<String, Object>>) customer.get("relationships");
        assertEquals(1, customerRels.size());
        assertEquals("orders", customerRels.get(0).get("field"));
        assertEquals("OneToMany", customerRels.get(0).get("kind"));
        assertEquals("com.fw.Order", customerRels.get(0).get("target"),
            "target resolved through the List<Order> type argument");
        assertEquals("customer", customerRels.get(0).get("mappedBy"));

        Map<String, Object> order = entities.get(1);
        assertEquals("com.fw.Order", order.get("name"));
        assertNull(order.get("table"), "Order has no @Table");
        assertEquals("id", order.get("idField"));

        List<Map<String, Object>> orderRels = (List<Map<String, Object>>) order.get("relationships");
        assertEquals(1, orderRels.size());
        assertEquals("customer", orderRels.get(0).get("field"));
        assertEquals("ManyToOne", orderRels.get(0).get("kind"));
        assertEquals("com.fw.Customer", orderRels.get(0).get("target"));
        assertNull(orderRels.get(0).get("mappedBy"), "owning side has no mappedBy");
    }

    @Test
    @DisplayName("non-relationship, non-id fields are not reported as relationships")
    @SuppressWarnings("unchecked")
    void plainFieldsExcluded() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess());

        Map<String, Object> customer = entities(r).get(0);
        List<Map<String, Object>> rels = (List<Map<String, Object>>) customer.get("relationships");
        assertTrue(rels.stream().noneMatch(rel -> "name".equals(rel.get("field"))),
            "plain String field must not appear as a relationship");
    }

    @Test
    @DisplayName("maxResults boundaries: 0, 1, total, total+1, MAX")
    void maxResultsBoundaries() {
        int[][] cases = {{0, 0}, {1, 1}, {2, 2}, {3, 2}, {Integer.MAX_VALUE, 2}};
        for (int[] c : cases) {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("maxResults", c[0]);
            ToolResponse r = tool.execute(args);
            assertTrue(r.isSuccess(), "maxResults=" + c[0]);
            assertEquals(c[1], entities(r).size(), "maxResults=" + c[0]);
            assertEquals(2, r.getMeta().getTotalCount(), "maxResults=" + c[0]);
        }
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
        GetJpaModelTool unloaded = new GetJpaModelTool(() -> null);
        ToolResponse r = unloaded.execute(objectMapper.createObjectNode());
        assertFalse(r.isSuccess());
        assertEquals("PROJECT_NOT_LOADED", r.getError().getCode());
    }
}
