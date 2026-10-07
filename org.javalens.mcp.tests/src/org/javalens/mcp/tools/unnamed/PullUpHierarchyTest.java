package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.PullUpTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.invalidParameter;

/**
 * pull_up refuses when JDT's type hierarchy of the destination does not hold every subtype the
 * Java model holds, under its own handle, or holds one the model does not: JDT's pull up reads
 * every subtype of the destination from that hierarchy
 * (PullUpRefactoringProcessor.getDestinationTypeHierarchy :2234-2242), for the units it changes
 * (getAffectedCompilationUnits :2162-2172), the members it checks (getMatchingMembers :2271-2276
 * through checkMembersInTypeAndAllSubtypes :1538-1559) and the supertypes it skips
 * (getSkippedSuperTypes :2372-2386). The fixture is pull-up-hierarchy (package
 * com.example.hierarchy):
 *
 * <ul>
 *   <li>Kiln.java: member classes Vessel and Jar extends Vessel (depth(), line 10); in fire() a
 *       lambda body declares the local class Urn extends Jar. JDT's hierarchy leaves out a class
 *       declared in a lambda body (HierarchyResolver.rememberAllTypes :511-541, the lambda
 *       parenting of HandleFactory :235-246).</li>
 *   <li>Mosaic.java: the first 32 lines of lambda-hierarchy's Mosaic.java, byte for byte: member
 *       class Tile; in pieces() two local classes Echo in sibling blocks, the first extending Tile,
 *       the second extending nothing; then the member class Slate extends Tile (veneer(), line 34).
 *       JDT's hierarchy numbers the two Echo classes in the order of the unit's local types map
 *       (HandleFactory :199-287 over CompilationUnitDeclaration.localTypes), not in source order,
 *       so it holds the first Echo, Tile's subtype, by the handle of the second.</li>
 * </ul>
 */
class PullUpHierarchyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String FIXTURE = "pull-up-hierarchy";
    private static final String PACKAGE = "com.example.hierarchy.";

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("pull_up of Jar.depth into Vessel is INVALID_PARAMETER naming Urn, a subtype declared in a lambda body that JDT's hierarchy of Vessel leaves out")
    void deeperSubtypeInLambda() {
        assertPayload(mapper, new PullUpTool(() -> service), envelope, position("Kiln.java", 10, 12),
            invalidParameter("Invalid parameter 'position': Member at position, depth in " + PACKAGE + "Kiln$Jar, "
                + "cannot be pulled up: JDT's pull up reads the subtypes of " + PACKAGE + "Kiln$Vessel from its own "
                + "type hierarchy, which leaves out " + PACKAGE + "Kiln$Urn"));
    }

    @Test
    @DisplayName("pull_up of Slate.veneer into Tile is INVALID_PARAMETER naming the first Echo, which JDT's hierarchy of Tile holds by the handle of the second")
    void misnumberedSibling() {
        assertPayload(mapper, new PullUpTool(() -> service), envelope, position("Mosaic.java", 34, 12),
            invalidParameter("Invalid parameter 'position': Member at position, veneer in " + PACKAGE + "Mosaic$Slate, "
                + "cannot be pulled up: JDT's pull up reads the subtypes of " + PACKAGE + "Mosaic$Tile from its own "
                + "type hierarchy, which leaves out " + PACKAGE + "Mosaic$Echo and holds " + PACKAGE + "Mosaic$Echo, "
                + "which is no subtype of " + PACKAGE + "Mosaic$Tile"));
    }

    private ObjectNode position(String file, int line, int column) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(FIXTURE).resolve("src/main/java/com/example/hierarchy/" + file)
            .toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }
}
