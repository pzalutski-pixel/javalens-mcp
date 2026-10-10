package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.search.SearchResult;
import org.javalens.core.search.SearchService;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.invalidParameter;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * change_method_signature renames a parameter as JDT's change-signature refactoring does
 * (ChangeSignatureProcessor.AbstractDeclarationUpdate.changeParamgumentName :2306-2318): a
 * parameter given with oldName is the parameter of that name, renamed; each declaration and each
 * lambda expression that names it so takes the new name in its parameter list and in every
 * reference to it in its body, a declaration's @param tag naming it too (changeJavadocTags
 * :2648-2656); a Javadoc reference naming the parameter takes the new name
 * (DocReferenceUpdate.changeParamgumentName :2910-2918); calls keep their arguments. A declaration
 * or lambda expression that names the parameter otherwise keeps its own name. The edits of the
 * lambda expressions implementing any of the related methods come in source order, file by file,
 * as the other places do.
 *
 * <p>The fixture is signature-params, package com.example.params (zero-based positions). Scaler
 * declares {@code int apply(int factor)} (line 13, return type at column 4, name at 8), its
 * Javadoc's {@code @param factor} naming it at line 10, column 14. Doubler overrides it as
 * {@code int apply(int factor)} (line 5, return type at 11) and reads factor at line 6, column 20,
 * and at line 7, columns 23 and 32; Halver overrides it as {@code int apply(int amount)} (line 5,
 * return type at 11). Lambdas's class Javadoc links {@code Scaler#apply(int factor)} (line 2,
 * column 15); Lambdas assigns {@code factor -> factor * 3} (line 5, column 20, the body's factor at
 * 30) and {@code (int n) -> n * 4} (line 6, column 18), and calls {@code scaler.apply(5)} (line 9,
 * column 15) and {@code triple.apply(6)} (column 33). Ints and Nums each declare
 * {@code int calc(int value)} (line 3, return type at 4, Ints's name at 8); Both implements both
 * with {@code int calc(int value)} (line 3, return type at 11), so the three change together.
 * Mixed assigns {@code value -> value + 1} to an Ints (line 3, column 17), {@code value -> value + 2}
 * to a Nums (line 4, column 18) and {@code value -> value + 3} to an Ints (line 5, column 17).
 * Zero declares {@code int zero()} (line 3, name at column 8), which has no parameter.
 *
 * <p>Further (zero-based positions): Guarded declares {@code int guard(final int limit, @Deprecated
 * int floor)} (line 3, return type at 4, name at 8, the list's closing parenthesis ending at 53),
 * returning limit (line 4, column 15), and calls {@code guard(1, 2)} (line 8, column 15). Recursive
 * declares {@code int count(int depth, int step)} (line 3) returning {@code depth <= 0 ? 0 :
 * count(depth - step, step)} (line 4: depth at 15, the call at 32 to 57). The interface Shifter
 * declares {@code int shift(int offset)} (line 3, name at 8); Mover implements it (line 10,
 * return type at 11), its Javadoc's {@code @param offset} at line 6, column 14, and reads offset in
 * a lambda body (line 11, column 51), in an anonymous class (line 14, column 23) and in a return
 * (line 18, column 15); ShiftUses's class Javadoc links {@code Shifter#shift(int x)} (line 2,
 * column 16) and assigns {@code (int offset) -> offset + 1} (line 4, at 20, the body's offset at
 * 36) and a block lambda {@code offset -> { ... }} (line 5, at 20) reading offset at line 6, column
 * 22. The interface Measure declares {@code long measure(int size)} (line 3, name at 9); Ruler
 * implements it (line 4, return type at 11) returning size (line 5, column 15) and calls
 * {@code measure(3)} (line 9, column 15). Swap declares {@code int diff(int a, int b)} (line 3)
 * returning {@code a - b} (line 4, a at 15, b at 19) and calls {@code diff(5, 3)} (line 8, column
 * 15). Box declares the field width and the constructor {@code Box(int width)} (line 5, column 4)
 * assigning {@code this.width = width} (the parameter at line 6, column 21), and creates
 * {@code new Box(4)} (line 10, column 15).
 * Raw offsets are not pinned and texts are compared with "\n" line endings.
 */
class SignatureParametersTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String FIXTURE = "signature-params";
    private static final String PARAMS = "src/main/java/com/example/params/";
    private static final String SCALER = PARAMS + "Scaler.java";
    private static final String DOUBLER = PARAMS + "Doubler.java";
    private static final String HALVER = PARAMS + "Halver.java";
    private static final String LAMBDAS = PARAMS + "Lambdas.java";
    private static final String INTS = PARAMS + "Ints.java";
    private static final String NUMS = PARAMS + "Nums.java";
    private static final String BOTH = PARAMS + "Both.java";
    private static final String MIXED = PARAMS + "Mixed.java";
    private static final String GUARDED = PARAMS + "Guarded.java";
    private static final String RECURSIVE = PARAMS + "Recursive.java";
    private static final String SHIFTER = PARAMS + "Shifter.java";
    private static final String MOVER = PARAMS + "Mover.java";
    private static final String SHIFT_USES = PARAMS + "ShiftUses.java";
    private static final String MEASURE = PARAMS + "Measure.java";
    private static final String RULER = PARAMS + "Ruler.java";
    private static final String SWAP = PARAMS + "Swap.java";
    private static final String BOX = PARAMS + "Box.java";

    private static final List<String> NEXT_TOOLS = List.of(
        "Apply the text edits to complete the signature change",
        "get_diagnostics to verify no errors");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("Renaming Scaler.apply's parameter factor to number renames it where Scaler, Doubler and the first lambda name it, in their bodies, the @param tag and the Javadoc link, and leaves calls and Halver's amount")
    void renameParameter() {
        assertChange(scalerArgs(helper.getFixturePath(FIXTURE)), renameParameterData());
    }

    @Test
    @DisplayName("A place one related method's search confirms is no potential match, whatever another method's search reports there")
    void confirmedPlaceIsNoPotentialMatch() throws Exception {
        JdtServiceImpl seamed = new ConfirmedElsewhereService();
        seamed.loadProject(helper.getFixturePath(FIXTURE));
        assertPayload(mapper, new ChangeMethodSignatureTool(() -> seamed), new EnvelopeHarness(seamed),
            scalerArgs(helper.getFixturePath(FIXTURE)),
            success(renameParameterData(), map("totalCount", 13, "returnedCount", 13, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    /**
     * The change of renaming Scaler.apply's parameter factor to number: 13 edits in Doubler, Halver,
     * Scaler and Lambdas, and no potential match.
     */
    private static Map<String, Object> renameParameterData() {
        return map(
            "oldName", "apply",
            "newName", "apply",
            "oldReturnType", "int",
            "newReturnType", "int",
            "oldParameterCount", 1,
            "newParameterCount", 1,
            "newParameters", List.of(map("name", "number", "type", "int")),
            "totalEdits", 13,
            "filesAffected", 4,
            "editsByFile", map(
                DOUBLER, List.of(
                    declaration(5, 11, 5, 32, "int apply(int factor)", "int apply(int number)"),
                    replace(6, 20, 6, 26, "factor", "number"),
                    replace(7, 23, 7, 29, "factor", "number"),
                    replace(7, 32, 7, 38, "factor", "number")),
                HALVER, List.of(
                    declaration(5, 11, 5, 32, "int apply(int amount)", "int apply(int amount)")),
                SCALER, List.of(
                    declaration(13, 4, 13, 25, "int apply(int factor)", "int apply(int number)"),
                    replace(10, 14, 10, 20, "factor", "number")),
                LAMBDAS, List.of(
                    replace(2, 15, 2, 39, "Scaler#apply(int factor)", "Scaler#apply(int number)"),
                    replace(9, 15, 9, 30, "scaler.apply(5)", "scaler.apply(5)"),
                    replace(9, 33, 9, 48, "triple.apply(6)", "triple.apply(6)"),
                    replace(5, 20, 5, 26, "factor", "number"),
                    replace(5, 30, 5, 36, "factor", "number"),
                    replace(6, 18, 6, 25, "(int n)", "(int n)"))));
    }

    /**
     * A service whose search for Doubler.apply also reports, as places it cannot confirm, every
     * reference and declaration the search for Scaler.apply confirms.
     */
    private static final class ConfirmedElsewhereService extends JdtServiceImpl {
        private SearchService search;

        @Override
        public synchronized SearchService getSearchService() {
            if (search == null) {
                search = new ConfirmedElsewhere(getJavaProject());
            }
            return search;
        }
    }

    private static final class ConfirmedElsewhere extends SearchService {
        private final IJavaProject project;

        ConfirmedElsewhere(IJavaProject project) {
            super(project);
            this.project = project;
        }

        @Override
        public SearchResult findAllReferencesInSources(IJavaElement element, int maxResults) throws CoreException {
            SearchResult own = super.findAllReferencesInSources(element, maxResults);
            return isDoubler(element)
                ? withUnconfirmed(own, super.findAllReferencesInSources(scaler(), maxResults).matches()) : own;
        }

        @Override
        public SearchResult findDeclarationsInSources(IJavaElement element, int maxResults) throws CoreException {
            SearchResult own = super.findDeclarationsInSources(element, maxResults);
            return isDoubler(element)
                ? withUnconfirmed(own, super.findDeclarationsInSources(scaler(), maxResults).matches()) : own;
        }

        private static boolean isDoubler(IJavaElement element) {
            return element instanceof IMethod method && method.getDeclaringType().getElementName().equals("Doubler");
        }

        private IMethod scaler() throws CoreException {
            return project.findType("com.example.params.Scaler").getMethod("apply", new String[] {"I"});
        }

        private static SearchResult withUnconfirmed(SearchResult own, List<SearchMatch> unconfirmed) {
            List<SearchMatch> potential = new ArrayList<>(own.potentialMatches());
            potential.addAll(unconfirmed);
            return new SearchResult(own.matches(), own.totalEncountered(), potential, potential.size());
        }
    }

    @Test
    @DisplayName("The renamed parameter, applied, compiles")
    void renameParameterCompiles() throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(FIXTURE);
        Path root = helper.getTempDirectory().resolve(FIXTURE);
        Map<String, List<Map<String, Object>>> edits = editsByFile(new ChangeMethodSignatureTool(() -> copy),
            scalerArgs(root));
        for (Map.Entry<String, List<Map<String, Object>>> file : edits.entrySet()) {
            Path path = root.resolve(file.getKey());
            Files.writeString(path, applied(Files.readString(path, StandardCharsets.UTF_8), file.getValue()),
                StandardCharsets.UTF_8);
        }
        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(root);
        List<String> files = List.of(SCALER, DOUBLER, HALVER, LAMBDAS);
        assertEquals(Set.copyOf(files), edits.keySet());
        assertAll(files.stream().map(file -> (Executable) () ->
            assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(file)), "errors in " + file)));
    }

    @Test
    @DisplayName("A parameter whose oldName names no parameter of the method is refused, naming the method's parameters")
    void oldNameOfNoParameter() {
        ObjectNode args = args(helper.getFixturePath(FIXTURE), SCALER, 13, 8,
            map("name", "number", "oldName", "size", "type", "int"));
        assertPayload(mapper, new ChangeMethodSignatureTool(() -> service), envelope, args,
            invalidParameter("Invalid parameter 'newParameters': oldName 'size' names no parameter of apply,"
                + " whose parameters are factor"));
    }

    @Test
    @DisplayName("A parameter with an oldName given to a method without parameters is refused, saying it has none")
    void oldNameOfMethodWithoutParameters() {
        ObjectNode args = args(helper.getFixturePath(FIXTURE), PARAMS + "Zero.java", 3, 8,
            map("name", "x", "oldName", "y", "type", "int"));
        assertPayload(mapper, new ChangeMethodSignatureTool(() -> service), envelope, args,
            invalidParameter("Invalid parameter 'newParameters': oldName 'y' names no parameter of zero,"
                + " which has none"));
    }

    @Test
    @DisplayName("Adding int step to Ints.calc, with Nums.calc and Both.calc, edits Mixed's three lambda expressions in source order")
    void lambdasOfRelatedMethodsInSourceOrder() {
        assertChange(args(helper.getFixturePath(FIXTURE), INTS, 3, 8,
                map("name", "value", "type", "int"), map("name", "step", "type", "int", "defaultValue", "1")),
            map(
                "oldName", "calc",
                "newName", "calc",
                "oldReturnType", "int",
                "newReturnType", "int",
                "oldParameterCount", 1,
                "newParameterCount", 2,
                "newParameters", List.of(map("name", "value", "type", "int"), map("name", "step", "type", "int")),
                "totalEdits", 6,
                "filesAffected", 4,
                "editsByFile", map(
                    BOTH, List.of(declaration(3, 11, 3, 30, "int calc(int value)", "int calc(int value, int step)")),
                    INTS, List.of(declaration(3, 4, 3, 23, "int calc(int value)", "int calc(int value, int step)")),
                    NUMS, List.of(declaration(3, 4, 3, 23, "int calc(int value)", "int calc(int value, int step)")),
                    MIXED, List.of(
                        replace(3, 17, 3, 22, "value", "(value, step)"),
                        replace(4, 18, 4, 23, "value", "(value, step)"),
                        replace(5, 17, 5, 22, "value", "(value, step)")))));
    }

    // ========== the method at position keeps its parameters as written ==========

    @Test
    @DisplayName("Reordering Guarded.guard's parameters and renaming limit to cap keeps final and @Deprecated, renames the body's limit and swaps the call's arguments")
    void keptParametersKeepModifiers() {
        assertChange(args(helper.getFixturePath(FIXTURE), GUARDED, 3, 8,
                map("name", "floor", "type", "int"), map("name", "cap", "oldName", "limit", "type", "int")),
            signature("guard", "int", 2, List.of(map("name", "floor", "type", "int"), map("name", "cap", "type", "int")),
                map(GUARDED, List.of(
                    declaration(3, 4, 3, 53, "int guard(final int limit, @Deprecated int floor)",
                        "int guard(@Deprecated int floor, final int cap)"),
                    replace(4, 15, 4, 20, "limit", "cap"),
                    replace(8, 15, 8, 26, "guard(1, 2)", "guard(2, 1)")))));
    }

    @Test
    @DisplayName("Changing Guarded.guard's floor to long keeps its @Deprecated")
    void changedTypeKeepsAnnotation() {
        assertChange(args(helper.getFixturePath(FIXTURE), GUARDED, 3, 8,
                map("name", "limit", "type", "int"), map("name", "floor", "type", "long")),
            signature("guard", "int", 2, List.of(map("name", "limit", "type", "int"), map("name", "floor", "type", "long")),
                map(GUARDED, List.of(
                    declaration(3, 4, 3, 53, "int guard(final int limit, @Deprecated int floor)",
                        "int guard(final int limit, @Deprecated long floor)"),
                    replace(8, 15, 8, 26, "guard(1, 2)", "guard(1, 2)")))));
    }

    @Test
    @DisplayName("Reordering Recursive.count's parameters and renaming depth to level renames depth inside the moved argument of the recursive call")
    void renamedParameterInsideMovedArgument() {
        assertChange(args(helper.getFixturePath(FIXTURE), RECURSIVE, 3, 8,
                map("name", "step", "type", "int"), map("name", "level", "oldName", "depth", "type", "int")),
            signature("count", "int", 2, List.of(map("name", "step", "type", "int"), map("name", "level", "type", "int")),
                map(RECURSIVE, List.of(
                    declaration(3, 4, 3, 34, "int count(int depth, int step)", "int count(int step, int level)"),
                    replace(4, 15, 4, 20, "depth", "level"),
                    replace(4, 32, 4, 57, "count(depth - step, step)", "count(step, level - step)")))));
    }

    // ========== renames across the kinds of places ==========

    @Test
    @DisplayName("Renaming Shifter.shift's offset to distance renames it in Mover's @param tag, in a lambda body and an anonymous class in Mover, in a typed and a block lambda, and in a Javadoc link giving it another name")
    void renameAcrossPlaces() {
        assertChange(args(helper.getFixturePath(FIXTURE), SHIFTER, 3, 8,
                map("name", "distance", "oldName", "offset", "type", "int")),
            signature("shift", "int", 1, List.of(map("name", "distance", "type", "int")), map(
                MOVER, List.of(
                    declaration(10, 11, 10, 32, "int shift(int offset)", "int shift(int distance)"),
                    replace(6, 14, 6, 20, "offset", "distance"),
                    replace(11, 51, 11, 57, "offset", "distance"),
                    replace(14, 23, 14, 29, "offset", "distance"),
                    replace(18, 15, 18, 21, "offset", "distance")),
                SHIFTER, List.of(
                    declaration(3, 4, 3, 25, "int shift(int offset)", "int shift(int distance)")),
                SHIFT_USES, List.of(
                    replace(2, 16, 2, 36, "Shifter#shift(int x)", "Shifter#shift(int distance)"),
                    replace(4, 20, 4, 32, "(int offset)", "(int distance)"),
                    replace(4, 36, 4, 42, "offset", "distance"),
                    replace(5, 20, 5, 26, "offset", "distance"),
                    replace(6, 22, 6, 28, "offset", "distance")))));
    }

    @Test
    @DisplayName("Renaming Measure.measure's size to length and changing it to long renames and retypes it in Measure and Ruler")
    void renameWithTypeChange() {
        assertChange(args(helper.getFixturePath(FIXTURE), MEASURE, 3, 9,
                map("name", "length", "oldName", "size", "type", "long")),
            signature("measure", "long", 1, List.of(map("name", "length", "type", "long")), map(
                MEASURE, List.of(
                    declaration(3, 4, 3, 26, "long measure(int size)", "long measure(long length)")),
                RULER, List.of(
                    declaration(4, 11, 4, 33, "long measure(int size)", "long measure(long length)"),
                    replace(5, 15, 5, 19, "size", "length"),
                    replace(9, 15, 9, 25, "measure(3)", "measure(3)")))));
    }

    @Test
    @DisplayName("Swapping Swap.diff's parameter names renames each where the other was, calls keeping their arguments")
    void swapOfNames() {
        assertChange(args(helper.getFixturePath(FIXTURE), SWAP, 3, 8,
                map("name", "b", "oldName", "a", "type", "int"), map("name", "a", "oldName", "b", "type", "int")),
            signature("diff", "int", 2, List.of(map("name", "b", "type", "int"), map("name", "a", "type", "int")),
                map(SWAP, List.of(
                    declaration(3, 4, 3, 26, "int diff(int a, int b)", "int diff(int b, int a)"),
                    replace(4, 15, 4, 16, "a", "b"),
                    replace(4, 19, 4, 20, "b", "a"),
                    replace(8, 15, 8, 25, "diff(5, 3)", "diff(5, 3)")))));
    }

    @Test
    @DisplayName("Renaming the constructor Box's width to size renames the parameter, not the field it assigns")
    void constructorParameter() {
        assertChange(args(helper.getFixturePath(FIXTURE), BOX, 5, 4,
                map("name", "size", "oldName", "width", "type", "int")),
            map(
                "oldName", "Box",
                "newName", "Box",
                "oldParameterCount", 1,
                "newParameterCount", 1,
                "newParameters", List.of(map("name", "size", "type", "int")),
                "totalEdits", 3,
                "filesAffected", 1,
                "editsByFile", map(BOX, List.of(
                    declaration(5, 4, 5, 18, "Box(int width)", "Box(int size)"),
                    replace(6, 21, 6, 26, "width", "size"),
                    replace(10, 15, 10, 25, "new Box(4)", "new Box(4)")))));
    }

    @Test
    @DisplayName("Guarded.guard reordered with limit renamed to cap, its edits applied, compiles")
    void reorderAndRenameCompiles() throws Exception {
        assertAppliedCompiles(root -> args(root, GUARDED, 3, 8,
            map("name", "floor", "type", "int"), map("name", "cap", "oldName", "limit", "type", "int")), GUARDED);
    }

    @Test
    @DisplayName("Guarded.guard with floor changed to long, its edits applied, compiles")
    void typeChangeCompiles() throws Exception {
        assertAppliedCompiles(root -> args(root, GUARDED, 3, 8,
            map("name", "limit", "type", "int"), map("name", "floor", "type", "long")), GUARDED);
    }

    @Test
    @DisplayName("Recursive.count reordered with depth renamed to level, its edits applied, compiles")
    void renameInsideMovedArgumentCompiles() throws Exception {
        assertAppliedCompiles(root -> args(root, RECURSIVE, 3, 8,
            map("name", "step", "type", "int"), map("name", "level", "oldName", "depth", "type", "int")), RECURSIVE);
    }

    @Test
    @DisplayName("Shifter.shift with offset renamed to distance, its edits applied, compiles")
    void renameAcrossPlacesCompiles() throws Exception {
        assertAppliedCompiles(root -> args(root, SHIFTER, 3, 8,
            map("name", "distance", "oldName", "offset", "type", "int")), MOVER, SHIFTER, SHIFT_USES);
    }

    @Test
    @DisplayName("Measure.measure with size renamed to length and changed to long, its edits applied, compiles")
    void renameWithTypeChangeCompiles() throws Exception {
        assertAppliedCompiles(root -> args(root, MEASURE, 3, 9,
            map("name", "length", "oldName", "size", "type", "long")), MEASURE, RULER);
    }

    @Test
    @DisplayName("Swap.diff with its parameter names swapped, its edits applied, compiles")
    void swapOfNamesCompiles() throws Exception {
        assertAppliedCompiles(root -> args(root, SWAP, 3, 8,
            map("name", "b", "oldName", "a", "type", "int"), map("name", "a", "oldName", "b", "type", "int")), SWAP);
    }

    @Test
    @DisplayName("The constructor Box with width renamed to size, its edits applied, compiles")
    void constructorParameterCompiles() throws Exception {
        assertAppliedCompiles(root -> args(root, BOX, 5, 4,
            map("name", "size", "oldName", "width", "type", "int")), BOX);
    }

    @Test
    @DisplayName("Ints.calc, with Nums.calc and Both.calc, given int step, its edits applied, compiles")
    void lambdasOfRelatedMethodsCompile() throws Exception {
        assertAppliedCompiles(root -> args(root, INTS, 3, 8,
            map("name", "value", "type", "int"), map("name", "step", "type", "int", "defaultValue", "1")),
            BOTH, INTS, NUMS, MIXED);
    }

    // ========== names that collide ==========

    @Test
    @DisplayName("Renaming factor to twice, the name of a local of Doubler.apply, is refused, naming the compile error and the declaration")
    void renameToLocalNameRefused() {
        assertRefused(args(helper.getFixturePath(FIXTURE), SCALER, 13, 8,
                map("name", "twice", "oldName", "factor", "type", "int")),
            "Duplicate local variable twice, in Doubler.apply at " + DOUBLER + ":5:11");
    }

    @Test
    @DisplayName("Renaming factor to amount, the name Halver.apply gives its parameter, is refused as JDT refuses it")
    void renameToRelatedMethodsParameterNameRefused() {
        assertRefused(args(helper.getFixturePath(FIXTURE), SCALER, 13, 8,
                map("name", "amount", "oldName", "factor", "type", "int")),
            "Method 'int apply(int amount)' already has a parameter named 'amount'.");
    }

    @Test
    @DisplayName("Two parameters of one name are refused")
    void duplicateNamesRefused() {
        assertRefused(args(helper.getFixturePath(FIXTURE), SWAP, 3, 8,
                map("name", "a", "type", "int"), map("name", "a", "type", "int")),
            "Duplicate parameter name: 'a'.");
    }

    @Test
    @DisplayName("A parameter name that is no Java identifier is refused")
    void invalidNameRefused() {
        assertRefused(args(helper.getFixturePath(FIXTURE), SWAP, 3, 8,
                map("name", "1x", "oldName", "a", "type", "int"), map("name", "b", "type", "int")),
            "'1x' is not a valid Java identifier");
    }

    @Test
    @DisplayName("An oldName given to two parameters is refused")
    void oldNameGivenTwiceRefused() {
        assertRefused(args(helper.getFixturePath(FIXTURE), SWAP, 3, 8,
                map("name", "x", "oldName", "a", "type", "int"), map("name", "y", "oldName", "a", "type", "int")),
            "oldName 'a' is given to more than one parameter");
    }

    @Test
    @DisplayName("A parameter renamed to an uppercase name goes ahead with JDT's naming warning")
    void uppercaseNameWarned() {
        Map<String, Object> data = signature("diff", "int", 2,
            List.of(map("name", "A", "type", "int"), map("name", "b", "type", "int")),
            map(SWAP, List.of(
                declaration(3, 4, 3, 26, "int diff(int a, int b)", "int diff(int A, int b)"),
                replace(4, 15, 4, 16, "a", "A"),
                replace(8, 15, 8, 25, "diff(5, 3)", "diff(5, 3)"))));
        data.put("warnings", List.of("This name is discouraged. According to convention, names of local variables"
            + " should start with a lowercase letter."));
        assertChange(args(helper.getFixturePath(FIXTURE), SWAP, 3, 8,
            map("name", "A", "oldName", "a", "type", "int"), map("name", "b", "type", "int")), data);
    }

    /**
     * The change of a method {@code name} returning {@code returnType} with {@code oldCount}
     * parameters to {@code newParameters}, by {@code editsByFile}.
     */
    private static Map<String, Object> signature(String name, String returnType, int oldCount,
                                                 List<Map<String, Object>> newParameters,
                                                 Map<String, Object> editsByFile) {
        int total = editsByFile.values().stream().mapToInt(edits -> ((List<?>) edits).size()).sum();
        return map(
            "oldName", name,
            "newName", name,
            "oldReturnType", returnType,
            "newReturnType", returnType,
            "oldParameterCount", oldCount,
            "newParameterCount", newParameters.size(),
            "newParameters", newParameters,
            "totalEdits", total,
            "filesAffected", editsByFile.size(),
            "editsByFile", editsByFile);
    }

    private void assertRefused(ObjectNode args, String reason) {
        assertPayload(mapper, new ChangeMethodSignatureTool(() -> service), envelope, args,
            invalidParameter("Invalid parameter 'newParameters': " + reason));
    }

    /**
     * The fixture copied, the change {@code args} gives for its root applied to it, and
     * {@code files}, every file the change edits, without compile errors.
     */
    private void assertAppliedCompiles(Function<Path, ObjectNode> args, String... files)
            throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(FIXTURE);
        Path root = helper.getTempDirectory().resolve(FIXTURE);
        Map<String, List<Map<String, Object>>> edits = editsByFile(new ChangeMethodSignatureTool(() -> copy),
            args.apply(root));
        for (Map.Entry<String, List<Map<String, Object>>> file : edits.entrySet()) {
            Path path = root.resolve(file.getKey());
            Files.writeString(path, applied(Files.readString(path, StandardCharsets.UTF_8), file.getValue()),
                StandardCharsets.UTF_8);
        }
        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(root);
        assertEquals(Set.of(files), edits.keySet());
        assertAll(List.of(files).stream().map(file -> (Executable) () ->
            assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(file)), "errors in " + file)));
    }

    private ObjectNode scalerArgs(Path root) {
        return args(root, SCALER, 13, 8, map("name", "number", "oldName", "factor", "type", "int"));
    }

    private void assertChange(ObjectNode args, Map<String, Object> data) {
        int total = (int) data.get("totalEdits");
        assertPayload(mapper, new ChangeMethodSignatureTool(() -> service), envelope, args,
            success(data, map("totalCount", total, "returnedCount", total, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    /** A declaration edit, its raw offsets not pinned. */
    private static Map<String, Object> declaration(int startLine, int startColumn, int endLine, int endColumn,
                                                   String oldSignature, String newSignature) {
        return map("type", "replace", "startLine", startLine, "startColumn", startColumn, "endLine", endLine,
            "endColumn", endColumn, "oldSignature", oldSignature, "newSignature", newSignature, "isDeclaration", true);
    }

    /** A replacement of the text from start to end by {@code newText}, its raw offsets not pinned. */
    private static Map<String, Object> replace(int startLine, int startColumn, int endLine, int endColumn,
                                               String oldText, String newText) {
        return map("type", "replace", "startLine", startLine, "startColumn", startColumn, "endLine", endLine,
            "endColumn", endColumn, "oldText", oldText, "newText", newText, "isDeclaration", false);
    }

    @SafeVarargs
    private ObjectNode args(Path root, String file, int line, int column, Map<String, Object>... parameters) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve(file).toString());
        args.put("line", line);
        args.put("column", column);
        ArrayNode list = args.putArray("newParameters");
        for (Map<String, Object> parameter : parameters) {
            list.add(mapper.valueToTree(parameter));
        }
        return args;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Map<String, Object>>> editsByFile(ChangeMethodSignatureTool tool, ObjectNode args) {
        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(), () -> "change_method_signature failed: " + response.getError());
        return (Map<String, List<Map<String, Object>>>) ((Map<String, Object>) response.getData()).get("editsByFile");
    }

    /**
     * {@code source} with {@code edits} applied, the last in the source first: a declaration's text
     * is its signature.
     */
    private static String applied(String source, List<Map<String, Object>> edits) {
        List<Map<String, Object>> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt((Map<String, Object> edit) ->
            ((Number) edit.get("startOffset")).intValue()).reversed());
        StringBuilder text = new StringBuilder(source);
        for (Map<String, Object> edit : ordered) {
            String newText = (String) (Boolean.TRUE.equals(edit.get("isDeclaration"))
                ? edit.get("newSignature") : edit.get("newText"));
            text.replace(((Number) edit.get("startOffset")).intValue(), ((Number) edit.get("endOffset")).intValue(),
                newText);
        }
        return text.toString();
    }
}
