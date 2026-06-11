package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetDiRegistrationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First positive-content coverage for get_di_registrations: framework-maven
 * carries real (stubbed-annotation) Spring DI usage, so every category is
 * asserted with exact counts and annotation labels. The zero-content path is
 * pinned by GetDiRegistrationsToolTest against simple-maven.
 */
class GetDiRegistrationsPositiveTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetDiRegistrationsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("framework-maven");
        tool = new GetDiRegistrationsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("reports exact counts and annotation labels for every DI category")
    @SuppressWarnings("unchecked")
    void exactDiInventory() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess(), () -> "expected success; got: " + r.getError());

        Map<String, Object> data = getData(r);
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        // 4 components: @Component WiredConsumer, @Service GreetingService,
        // and the two @RestController classes (RestController is a stereotype).
        assertEquals(4, summary.get("components"), () -> "summary: " + summary);
        assertEquals(1, summary.get("configurations"));
        assertEquals(1, summary.get("beans"));
        assertEquals(1, summary.get("injectionPoints"));

        List<Map<String, Object>> components = (List<Map<String, Object>>) data.get("components");
        Set<String> componentAnnotations = components.stream()
            .map(c -> (String) c.get("annotation"))
            .collect(Collectors.toSet());
        assertEquals(Set.of("@Component", "@Service", "@RestController"), componentAnnotations);

        Set<String> componentFiles = components.stream()
            .map(c -> ((String) c.get("filePath")).replace('\\', '/'))
            .collect(Collectors.toSet());
        assertEquals(Set.of(
            "src/main/java/com/fw/GreetingService.java",
            "src/main/java/com/fw/WiredConsumer.java",
            "src/main/java/com/fw/OrderController.java",
            "src/main/java/com/fw/StatusController.java"),
            componentFiles);

        List<Map<String, Object>> beans = (List<Map<String, Object>>) data.get("beans");
        assertTrue(((String) beans.get(0).get("filePath")).replace('\\', '/')
            .endsWith("com/fw/AppConfig.java"), () -> "beans: " + beans);
    }
}
