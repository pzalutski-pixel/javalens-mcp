package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetDiRegistrationsTool;
import org.javalens.mcp.tools.GetHttpEndpointsTool;
import org.javalens.mcp.tools.GetJpaModelTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.PotentialMatches.DRIFT;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.at;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.potential;
import static org.javalens.mcp.tools.unnamed.PotentialMatches.withoutOffsets;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * The framework tools register only what an annotation the compiler resolves declares. Drift's
 * annotations name Entity, Component and GetMapping without importing them, so the search for each
 * annotation type reports them as potential matches (TypeReferenceLocator.resolveLevel(
 * TypeReference) :758-768): no entity, component or route is listed, and each place is reported
 * in potentialMatches with the annotation it names. The fixture is potential-matches
 * ({@link PotentialMatches}).
 */
class PotentialFrameworkTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(PotentialMatches.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("get_di_registrations lists no component; Drift's unresolved @Component is a potential match")
    void diRegistrations() {
        Map<String, Object> data = map(
            "summary", map("components", 0, "configurations", 0, "beans", 0, "injectionPoints", 0),
            "components", List.of(),
            "configurations", List.of(),
            "beans", List.of(),
            "injectionPoints", List.of(),
            "potentialMatches", potential(1, List.of(annotated(at(DRIFT, 3, 1, 9, "@Component"), "@Component"))));
        Map<String, Object> meta = map("totalCount", 0, "returnedCount", 0, "truncated", false,
            "suggestedNextTools", List.of(
                "find_references to trace a specific dependency",
                "analyze_type for details on a registered component"));
        assertPayload(mapper, new GetDiRegistrationsTool(() -> service), envelope, mapper.createObjectNode(),
            success(data, meta), withoutOffsets());
    }

    @Test
    @DisplayName("get_http_endpoints lists no route; Drift.list's unresolved @GetMapping is a potential match")
    void httpEndpoints() {
        Map<String, Object> data = map(
            "endpointCount", 0,
            "endpoints", List.of(),
            "potentialMatches", potential(1, List.of(annotated(at(DRIFT, 6, 5, 10, "@GetMapping"), "@GetMapping"))));
        Map<String, Object> meta = map("totalCount", 0, "returnedCount", 0, "truncated", false,
            "suggestedNextTools", List.of(
                "analyze_method on a handler for its callers and callees",
                "find_affected_tests to see which tests cover a handler"));
        assertPayload(mapper, new GetHttpEndpointsTool(() -> service), envelope, mapper.createObjectNode(),
            success(data, meta), withoutOffsets());
    }

    @Test
    @DisplayName("get_jpa_model lists no entity; Drift's unresolved @Entity is a potential match")
    void jpaModel() {
        Map<String, Object> data = map(
            "entityCount", 0,
            "entities", List.of(),
            "potentialMatches", potential(1, List.of(annotated(at(DRIFT, 2, 1, 6, "@Entity"), "@Entity"))));
        Map<String, Object> meta = map("totalCount", 0, "returnedCount", 0, "truncated", false,
            "suggestedNextTools", List.of(
                "analyze_type for full details of an entity",
                "find_references to see where an entity is used"));
        assertPayload(mapper, new GetJpaModelTool(() -> service), envelope, mapper.createObjectNode(),
            success(data, meta), withoutOffsets());
    }

    private static Map<String, Object> annotated(Map<String, Object> location, String annotation) {
        location.put("annotation", annotation);
        return location;
    }
}
