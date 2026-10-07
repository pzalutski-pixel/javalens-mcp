package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.SearchSymbolsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.EVENTS;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.EVENTS_TYPE;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.LOOSE;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;

/**
 * search_symbols matches type declarations by name, so an anonymous class, which has none,
 * is never a result; local classes are. A type in the default package reports its package as
 * "(default package)".
 */
class AnonymousTypeSearchTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /**
     * Every named type of the fixture in search order: Loose.java before Events.java (paths
     * compare by name), then in Events.java the type itself, the local classes of its methods
     * (Counter in tally(), Auditor in audit()) and its member classes (Sink, Recorder). The
     * three anonymous classes are not among them.
     */
    private static final List<Map<String, Object>> NAMED_TYPES = List.of(
        type("Loose", LOOSE, 0, 13, "Loose", "(default package)"),
        type("Events", EVENTS, 6, 13, EVENTS_TYPE, "com.example.unnamed"),
        type("Counter", EVENTS, 68, 14, EVENTS_TYPE + "$Counter", "com.example.unnamed"),
        type("Auditor", EVENTS, 86, 14, EVENTS_TYPE + "$Auditor", "com.example.unnamed"),
        type("Sink", EVENTS, 20, 26, EVENTS_TYPE + "$Sink", "com.example.unnamed"),
        type("Recorder", EVENTS, 24, 17, EVENTS_TYPE + "$Recorder", "com.example.unnamed"));

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(UnnamedElements.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("search_symbols(\"*\") returns the six named types and none of the three anonymous classes")
    void wildcard_excludesAnonymousTypes() {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "*");
        assertPayload(mapper, new SearchSymbolsTool(() -> service), envelope, args, page(null));
    }

    @Test
    @DisplayName("search_symbols(\"*\", kind=class) returns the six named classes and none of the three anonymous classes")
    void wildcardClasses_excludesAnonymousTypes() {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "*");
        args.put("kind", "class");
        assertPayload(mapper, new SearchSymbolsTool(() -> service), envelope, args, page("class"));
    }

    private static Map<String, Object> page(String kind) {
        Map<String, Object> data = map("query", "*");
        if (kind != null) {
            data.put("kind", kind);
        }
        data.put("results", NAMED_TYPES);
        data.put("pagination", map("offset", 0, "returned", 6, "hasMore", false));
        Map<String, Object> meta = map(
            "totalCount", 6,
            "returnedCount", 6,
            "truncated", false,
            "suggestedNextTools", List.of(
                "get_symbol_info at a result location for detailed info",
                "get_type_members for type results",
                "find_references to see all usages"));
        return success(data, meta);
    }

    private static Map<String, Object> type(String name, String file, int line, int column, String qualifiedName,
                                            String pkg) {
        return map("name", name, "kind", "class", "filePath", file, "line", line, "column", column,
            "qualifiedName", qualifiedName, "package", pkg);
    }
}
