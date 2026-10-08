package org.javalens.core;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModelStatusConstants;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IOpenable;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameRequestor;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.index.EntryResult;
import org.eclipse.jdt.internal.core.index.Index;
import org.eclipse.jdt.internal.core.search.indexing.IIndexConstants;
import org.eclipse.jdt.internal.core.search.indexing.ReadWriteMonitor;
import org.eclipse.jdt.internal.core.search.matching.MethodPattern;
import org.eclipse.jdt.internal.core.search.matching.TypeDeclarationPattern;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.ProjectImporter;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.search.SearchService;
import org.javalens.core.workspace.WorkspaceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search index is verified against the compilation units it describes: every unit at load,
 * and each file a disk-sync repair re-reads. A unit whose document lacks a top-level type the
 * unit declares is re-indexed and verified again; a gap that survives is reported by
 * {@code INDEX_INCOMPLETE} and by {@code getIndexIncompleteFiles} until a later verification
 * finds the file complete.
 *
 * <p>Each gap is a real one in the project's index. The test removes a document's entries
 * through JDT's {@link Index} API under the index's write lock, as JDT's own RemoveFromIndex
 * job does; for the shape JDT's indexer can leave behind, it then writes back the entries
 * JDT's SourceIndexer records for a lambda, so the document keeps a lambda pseudo-type and
 * nothing it declares. A gap survives the repair only through the service's package-private
 * {@code setIndexRepairEnabled(false)} seam, which keeps the repair from re-indexing. A file is
 * deleted, or fails to be read, just as the verification reads it through IndexVerifier's
 * package-private {@code declaredTypes}, overridden in a verifier the load gets through the
 * service's package-private {@code setIndexVerifiers} seam.
 */
class IndexVerifierTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String FIXTURE = "index-verification";

    private static final String SOURCES = "src/main/java/org/example/verify/";
    private static final String ACCOUNTS = SOURCES + "Accounts.java";
    private static final String LEDGER = SOURCES + "Ledger.java";
    private static final String NOTES = SOURCES + "Notes.java";
    private static final String PACKAGE_INFO = SOURCES + "package-info.java";
    private static final String POSTING = SOURCES + "Posting.java";

    private static final String ACCOUNT_BOOK_TYPE = "org.example.verify.AccountBook";
    private static final String LEDGER_TYPE = "org.example.verify.Ledger";
    private static final String ENTRY_TYPE = "org.example.verify.Ledger.Entry";
    private static final String POSTING_TYPE = "org.example.verify.Posting";

    /** Every type the fixture declares, top-level and member. */
    private static final Set<String> DECLARED_TYPES = Set.of(ACCOUNT_BOOK_TYPE, LEDGER_TYPE, ENTRY_TYPE, POSTING_TYPE);

    /**
     * The simple name of each type the fixture declares, each searched on its own. A wildcard
     * query would match a lambda pseudo-type entry too, and the match locator then reports every
     * type of that document, so only by-name queries show what a by-name search_symbols misses.
     */
    private static final List<String> TYPE_QUERIES = List.of("AccountBook", "Entry", "Ledger", "Posting");

    /** Every reference to Ledger.Entry in the fixture, as file:line (0-based). */
    private static final List<String> ENTRY_REFERENCES = List.of(
        "Accounts.java:7",
        "Accounts.java:8",
        "Ledger.java:14",
        "Ledger.java:16",
        "Ledger.java:23",
        "Ledger.java:28",
        "Posting.java:14");

    /** Posting.java with a method {@code reverse} taking a Ledger.Entry on line 17 (0-based). */
    private static final String POSTING_WITH_REVERSE = """
        package org.example.verify;

        /**
         * Package-private type without lambdas that posts entries to a ledger.
         */
        final class Posting {

            private final Ledger ledger;

            Posting(Ledger ledger) {
                this.ledger = ledger;
            }

            void post(String account, long cents) {
                ledger.add(new Ledger.Entry(account, cents));
            }

            void reverse(Ledger.Entry entry) {
                post(entry.account(), -entry.cents());
            }
        }
        """;

    /** Every reference to Ledger.Entry once Posting.java is {@link #POSTING_WITH_REVERSE}. */
    private static final List<String> ENTRY_REFERENCES_WITH_REVERSE = List.of(
        "Accounts.java:7",
        "Accounts.java:8",
        "Ledger.java:14",
        "Ledger.java:16",
        "Ledger.java:23",
        "Ledger.java:28",
        "Posting.java:14",
        "Posting.java:17");

    /**
     * The typeDecl key JDT's SourceIndexer writes for a lambda's pseudo-type: modifiers 0, name
     * "0", no package, and enclosing names {"0"}, JDT's mark of a local type, as a secondary type.
     */
    private static final char[] LAMBDA_TYPE_KEY = TypeDeclarationPattern.createIndexKey(
        0, IIndexConstants.ONE_ZERO, CharOperation.NO_CHAR, IIndexConstants.ONE_ZERO_CHAR, true);

    /** The methodDecl key JDT's SourceIndexer writes for Ledger's lambda, a one-argument Predicate.test. */
    private static final char[] LAMBDA_METHOD_KEY = MethodPattern.createIndexKey("test".toCharArray(), 1);

    private static final String RELOAD_OR_RESTART = "Reload the project; if the warning persists, restart JavaLens.";

    /** The warning a model of {@link #addingModelWarning} carries. */
    private static final LoadWarning MODEL_WARNING = new LoadWarning(
        LoadWarning.MODULE_LAYOUT_ASSUMED, "model warning added by the test importer", "none");

    /** The reason {@link #refusingToRemove} gives for not removing a project. */
    private static final String REMOVAL_FAILURE = "a file in its directory is locked";

    /** The reading error of a source file that exists but cannot be read. */
    private static final String UNREADABLE = "Posting.java cannot be read: another process holds a lock on it";

    // ========== Verification ==========

    @Test
    @DisplayName("on a complete index no unit is a gap, including a unit that declares no type and a unit whose only type is not named after its file")
    void completeIndex_noGap() throws Exception {
        JdtServiceImpl service = helper.loadProject(FIXTURE);
        IJavaProject project = service.getJavaProject();
        List<ICompilationUnit> units = sourceUnits(project);

        assertAll(
            () -> assertEquals(List.of(ACCOUNTS, LEDGER, NOTES, POSTING, PACKAGE_INFO), paths(service, units)),
            () -> assertEquals(List.of(), paths(service, new IndexVerifier(project).findGaps(units))),
            () -> assertEquals(DECLARED_TYPES, symbolSearchTypes(service.getSearchService())),
            () -> assertEquals(List.of(), service.getIndexIncompleteFiles()),
            () -> assertEquals(List.of(), service.getWarnings()));
    }

    @Test
    @DisplayName("a document whose entries are removed from the index is exactly the gap")
    void removedDocument_isTheGap() throws Exception {
        JdtServiceImpl service = helper.loadProject(FIXTURE);
        IJavaProject project = service.getJavaProject();

        induceGap(unit(project, "Posting.java"), GapShape.REMOVED);

        assertAll(
            () -> assertEquals(Set.of(ACCOUNT_BOOK_TYPE, LEDGER_TYPE, ENTRY_TYPE), symbolSearchTypes(service.getSearchService())),
            () -> assertEquals(List.of(POSTING), paths(service, new IndexVerifier(project).findGaps(sourceUnits(project)))));
    }

    @Test
    @DisplayName("a document left with only the entries JDT writes for a lambda, a lambda pseudo-type among them, is exactly the gap")
    void lambdaEntriesOnly_isTheGap() throws Exception {
        JdtServiceImpl service = helper.loadProject(FIXTURE);
        IJavaProject project = service.getJavaProject();
        ICompilationUnit ledger = unit(project, "Ledger.java");

        induceGap(ledger, GapShape.LAMBDA_ENTRIES_ONLY);

        assertAll(
            () -> assertEquals(List.of(new String(LAMBDA_TYPE_KEY)), typeDeclarationKeys(ledger)),
            () -> assertEquals(Set.of(ACCOUNT_BOOK_TYPE, POSTING_TYPE), symbolSearchTypes(service.getSearchService())),
            () -> assertEquals(List.of(LEDGER), paths(service, new IndexVerifier(project).findGaps(sourceUnits(project)))));
    }

    @Test
    @DisplayName("units that declare no type are satisfied with or without index entries; a unit whose only type is not named after its file is satisfied while that type is indexed and a gap once it is not")
    void typelessAndRenamedUnits_withAndWithoutEntries() throws Exception {
        JdtServiceImpl service = helper.loadProject(FIXTURE);
        IJavaProject project = service.getJavaProject();
        List<ICompilationUnit> units = List.of(
            unit(project, "Accounts.java"), unit(project, "Notes.java"), unit(project, "package-info.java"));
        IndexVerifier verifier = new IndexVerifier(project);

        List<String> gapsWhileIndexed = paths(service, verifier.findGaps(units));
        for (ICompilationUnit unit : units) {
            induceGap(unit, GapShape.REMOVED);
        }
        List<String> gapsWithoutEntries = paths(service, verifier.findGaps(units));

        assertAll(
            () -> assertEquals(List.of(), gapsWhileIndexed),
            () -> assertEquals(List.of(ACCOUNTS), gapsWithoutEntries));
    }

    @Test
    @DisplayName("verification opens only the units whose file name is not a top-level type indexed for them")
    void verification_opensOnlyUnitsNotNamedAfterAnIndexedType() throws Exception {
        JdtServiceImpl service = helper.loadProject(FIXTURE);
        IJavaProject project = service.getJavaProject();
        List<ICompilationUnit> units = sourceUnits(project);
        for (ICompilationUnit unit : units) {
            unit.close();
        }
        List<String> openBefore = paths(service, units.stream().filter(IOpenable::isOpen).toList());

        List<String> gaps = paths(service, new IndexVerifier(project).findGaps(units));

        assertAll(
            () -> assertEquals(List.of(), openBefore),
            () -> assertEquals(List.of(), gaps),
            () -> assertEquals(List.of(ACCOUNTS, NOTES, PACKAGE_INFO),
                paths(service, units.stream().filter(IOpenable::isOpen).toList())));
    }

    // ========== Repair at load ==========

    @Test
    @DisplayName("gaps present right after indexing are repaired by the load: no INDEX_INCOMPLETE, every type and every reference found, and the project tree unchanged byte for byte and time for time")
    void gapsAtLoad_repaired_projectTreeUnchanged() throws Exception {
        Path root = helper.copyFixture(FIXTURE);
        Map<String, TreeEntry> treeBefore = tree(root);
        GapInducingImporter importer = new GapInducingImporter(
            Map.of("Ledger.java", GapShape.LAMBDA_ENTRIES_ONLY, "Posting.java", GapShape.REMOVED), null);
        JdtServiceImpl service = new JdtServiceImpl(importer);

        service.loadProject(root);

        assertAll(
            () -> assertEquals(Set.of(ACCOUNT_BOOK_TYPE), importer.typesAfterGaps),
            () -> assertEquals(List.of(), service.getIndexIncompleteFiles()),
            () -> assertEquals(List.of(), service.getWarnings()),
            () -> assertEquals(DECLARED_TYPES, symbolSearchTypes(service.getSearchService())),
            () -> assertEquals(ENTRY_REFERENCES, references(service, ENTRY_TYPE)),
            () -> assertEquals(treeBefore, tree(root)));
    }

    // ========== A gap that survives the repair ==========

    @Test
    @DisplayName("gaps that survive the repair are named by INDEX_INCOMPLETE and getIndexIncompleteFiles, sorted, and searches lack exactly those files' types and references")
    void gapsSurvivingRepair_namedInWarningAndFiles() throws Exception {
        JdtServiceImpl service = new JdtServiceImpl(new GapInducingImporter(
            Map.of("Ledger.java", GapShape.LAMBDA_ENTRIES_ONLY, "Posting.java", GapShape.REMOVED), null));
        service.setIndexRepairEnabled(false);

        service.loadProject(helper.getFixturePath(FIXTURE));

        assertAll(
            () -> assertEquals(List.of(LEDGER, POSTING), service.getIndexIncompleteFiles()),
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.INDEX_INCOMPLETE,
                    "The search index is missing declarations from 2 files: " + LEDGER + ", " + POSTING
                        + "; search-based results do not include them",
                    RELOAD_OR_RESTART)),
                service.getWarnings()),
            () -> assertEquals(Set.of(ACCOUNT_BOOK_TYPE), symbolSearchTypes(service.getSearchService())),
            () -> assertEquals(List.of("Accounts.java:7", "Accounts.java:8"), references(service, ENTRY_TYPE)));
    }

    @Test
    @DisplayName("one file whose gap survives the repair is named in the singular")
    void gapSurvivingRepair_oneFile() throws Exception {
        JdtServiceImpl service = new JdtServiceImpl(new GapInducingImporter(
            Map.of("Posting.java", GapShape.REMOVED), null));
        service.setIndexRepairEnabled(false);

        service.loadProject(helper.getFixturePath(FIXTURE));

        assertAll(
            () -> assertEquals(List.of(POSTING), service.getIndexIncompleteFiles()),
            () -> assertEquals(List.of(indexIncompleteForPosting()), service.getWarnings()));
    }

    @Test
    @DisplayName("INDEX_INCOMPLETE comes after the model's warnings and before EARLIER_PROJECT_KEPT")
    void indexIncomplete_afterModelWarningsBeforeEarlierProjectKept() throws Exception {
        Path root = helper.getFixturePath(FIXTURE);
        JdtServiceImpl earlier = new JdtServiceImpl();
        earlier.loadProject(root);
        String earlierName = earlier.getJavaProject().getElementName();
        JdtServiceImpl reload = new JdtServiceImpl(
            new GapInducingImporter(Map.of("Posting.java", GapShape.REMOVED), MODEL_WARNING),
            refusingToRemove(earlierName));
        reload.setIndexRepairEnabled(false);

        reload.loadProject(root);

        assertEquals(List.of(
                MODEL_WARNING,
                indexIncompleteForPosting(),
                new LoadWarning(LoadWarning.EARLIER_PROJECT_KEPT,
                    "The earlier JavaLens project " + earlierName + " could not be removed: " + REMOVAL_FAILURE
                        + "; it stays in the workspace until the next load removes it",
                    RELOAD_OR_RESTART)),
            reload.getWarnings());
    }

    @Test
    @DisplayName("TIMEOUT_SECONDS_INVALID comes after the model's warnings and before INDEX_INCOMPLETE and EARLIER_PROJECT_KEPT")
    void timeoutSecondsInvalid_afterModelWarningsBeforeIndexIncomplete() throws Exception {
        Path root = helper.getFixturePath(FIXTURE);
        JdtServiceImpl earlier = new JdtServiceImpl();
        earlier.loadProject(root);
        String earlierName = earlier.getJavaProject().getElementName();
        JdtServiceImpl reload = new JdtServiceImpl(
            new GapInducingImporter(Map.of("Posting.java", GapShape.REMOVED), MODEL_WARNING),
            refusingToRemove(earlierName));
        reload.setIndexRepairEnabled(false);
        reload.setTimeoutSetting(TimeoutSetting.of("1000"));

        reload.loadProject(root);

        assertEquals(List.of(
                MODEL_WARNING,
                new LoadWarning(LoadWarning.TIMEOUT_SECONDS_INVALID,
                    "JAVALENS_TIMEOUT_SECONDS is \"1000\", above 300: each tool call is limited to 300 seconds",
                    "Set JAVALENS_TIMEOUT_SECONDS to a whole number of seconds from 5 to 300, or unset it for no "
                        + "limit, then restart JavaLens."),
                indexIncompleteForPosting(),
                new LoadWarning(LoadWarning.EARLIER_PROJECT_KEPT,
                    "The earlier JavaLens project " + earlierName + " could not be removed: " + REMOVAL_FAILURE
                        + "; it stays in the workspace until the next load removes it",
                    RELOAD_OR_RESTART)),
            reload.getWarnings());
    }

    @Test
    @DisplayName("a disk-sync repair of another file keeps a surviving gap; once the gap's file is re-read and found complete, INDEX_INCOMPLETE and the listed file are gone")
    void survivingGap_followsTheLatestVerification() throws Exception {
        Path root = helper.copyFixture(FIXTURE);
        JdtServiceImpl service = new JdtServiceImpl(new GapInducingImporter(
            Map.of("Posting.java", GapShape.REMOVED), null));
        service.setIndexRepairEnabled(false);
        service.loadProject(root);
        Path ledger = root.resolve(LEDGER);
        Path posting = root.resolve(POSTING);

        Files.writeString(ledger, Files.readString(ledger) + "// edited\n");
        List<Path> otherFileRepaired = service.ensureFresh();
        List<String> filesAfterOtherFile = service.getIndexIncompleteFiles();
        List<LoadWarning> warningsAfterOtherFile = service.getWarnings();
        service.setIndexRepairEnabled(true);
        Files.writeString(posting, Files.readString(posting) + "// edited\n");
        List<Path> gapFileRepaired = service.ensureFresh();

        assertAll(
            () -> assertEquals(List.of(ledger), otherFileRepaired),
            () -> assertEquals(List.of(POSTING), filesAfterOtherFile),
            () -> assertEquals(List.of(indexIncompleteForPosting()), warningsAfterOtherFile),
            () -> assertEquals(List.of(posting), gapFileRepaired),
            () -> assertEquals(List.of(), service.getIndexIncompleteFiles()),
            () -> assertEquals(List.of(), service.getWarnings()),
            () -> assertEquals(DECLARED_TYPES, symbolSearchTypes(service.getSearchService())),
            () -> assertEquals(ENTRY_REFERENCES, references(service, ENTRY_TYPE)));
    }

    @Test
    @DisplayName("once the file of a surviving gap is deleted, the disk-sync repair no longer names it")
    void survivingGap_fileDeleted_noLongerNamed() throws Exception {
        Path root = helper.copyFixture(FIXTURE);
        JdtServiceImpl service = new JdtServiceImpl(new GapInducingImporter(
            Map.of("Posting.java", GapShape.REMOVED), null));
        service.setIndexRepairEnabled(false);
        service.loadProject(root);
        List<String> filesBeforeDelete = service.getIndexIncompleteFiles();
        Path posting = root.resolve(POSTING);

        Files.delete(posting);
        List<Path> repaired = service.ensureFresh();

        assertAll(
            () -> assertEquals(List.of(POSTING), filesBeforeDelete),
            () -> assertEquals(List.of(posting), repaired),
            () -> assertEquals(List.of(), service.getIndexIncompleteFiles()),
            () -> assertEquals(List.of(), service.getWarnings()));
    }

    // ========== A file that cannot be read ==========

    @Test
    @DisplayName("a source file deleted between the verification's listing and its reading is skipped: the load completes, the file is in neither the gap set nor any warning, and the other units' result is exact")
    void unitDeletedBeforeReading_skipped() throws Exception {
        Path root = helper.copyFixture(FIXTURE);
        Path posting = root.resolve(POSTING);
        JdtServiceImpl service = new JdtServiceImpl(new GapInducingImporter(
            Map.of("Ledger.java", GapShape.LAMBDA_ENTRIES_ONLY, "Posting.java", GapShape.REMOVED), null));
        service.setIndexRepairEnabled(false);
        service.setIndexVerifiers(project -> new IndexVerifier(project) {
            @Override
            IType[] declaredTypes(ICompilationUnit unit) throws JavaModelException {
                if (unit.getElementName().equals("Posting.java")) {
                    deleteBeforeReading(unit, posting);
                }
                return super.declaredTypes(unit);
            }
        });

        service.loadProject(root);

        assertAll(
            () -> assertFalse(Files.exists(posting), "Posting.java is deleted before it is read"),
            () -> assertEquals(List.of(LEDGER), service.getIndexIncompleteFiles()),
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.INDEX_INCOMPLETE,
                    "The search index is missing declarations from 1 file: " + LEDGER
                        + "; search-based results do not include them",
                    RELOAD_OR_RESTART)),
                service.getWarnings()));
    }

    @Test
    @DisplayName("a source file that exists but cannot be read fails the load with the reading error, the load's project is removed and the service holds no project, as before the load")
    void unitUnreadable_failsLoad() throws Exception {
        Path root = helper.copyFixture(FIXTURE);
        // The form in which JDT reports any failure to read a file's contents.
        JavaModelException unreadable = new JavaModelException(
            new CoreException(new Status(IStatus.ERROR, "org.javalens.core.tests", UNREADABLE)),
            IJavaModelStatusConstants.ELEMENT_DOES_NOT_EXIST);
        JdtServiceImpl service = new JdtServiceImpl(new GapInducingImporter(
            Map.of("Posting.java", GapShape.REMOVED), null));
        service.setIndexVerifiers(project -> new IndexVerifier(project) {
            @Override
            IType[] declaredTypes(ICompilationUnit unit) throws JavaModelException {
                if (unit.getElementName().equals("Posting.java")) {
                    throw unreadable;
                }
                return super.declaredTypes(unit);
            }
        });

        List<String> projectsBefore = workspaceProjects();

        JavaModelException thrown = assertThrows(JavaModelException.class, () -> service.loadProject(root));

        assertAll(
            () -> assertSame(unreadable, thrown),
            () -> assertEquals(UNREADABLE, thrown.getMessage()),
            () -> assertTrue(Files.exists(root.resolve(POSTING)), "Posting.java still exists"),
            () -> assertEquals(projectsBefore, workspaceProjects(), "the failed load's project is removed"),
            () -> assertNull(service.getJavaProject(), "the service holds no project, as before the load"));
    }

    // ========== Disk sync ==========

    @Test
    @DisplayName("a gap on a file the disk-sync repair finds added, kept from re-indexing, is named by INDEX_INCOMPLETE")
    void addedFileWithGap_keptFromRepair_named() throws Exception {
        Path root = helper.copyFixture(FIXTURE);
        JdtServiceImpl service = new JdtServiceImpl();
        service.setIndexRepairEnabled(false);
        service.loadProject(root);
        Path refund = root.resolve(SOURCES + "Refund.java");
        Files.writeString(refund, "package org.example.verify;\n\nfinal class Refund {\n}\n");
        ICompilationUnit posting = unit(service.getJavaProject(), "Posting.java");
        IFile refundFile = ((IFile) posting.getResource()).getParent()
            .getFile(new org.eclipse.core.runtime.Path("Refund.java"));
        refundFile.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
        induceGap(unit(service.getJavaProject(), "Refund.java"), GapShape.REMOVED);

        List<Path> repaired = service.ensureFresh();

        assertAll(
            () -> assertEquals(List.of(refund), repaired),
            () -> assertEquals(List.of(SOURCES + "Refund.java"), service.getIndexIncompleteFiles()),
            () -> assertEquals(List.of(new LoadWarning(LoadWarning.INDEX_INCOMPLETE,
                    "The search index is missing declarations from 1 file: " + SOURCES + "Refund.java"
                        + "; search-based results do not include them",
                    RELOAD_OR_RESTART)),
                service.getWarnings()));
    }

    @Test
    @DisplayName("a gap on a file the disk-sync repair re-reads is detected and repaired: the file's type, its new method and its references are all found")
    void editedFileWithGap_repairedByDiskSync() throws Exception {
        Path root = helper.copyFixture(FIXTURE);
        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(root);

        Set<String> typesWithGap = editPostingThenInduceGap(service);
        List<Path> repaired = service.ensureFresh();

        assertAll(
            () -> assertEquals(Set.of(ACCOUNT_BOOK_TYPE, LEDGER_TYPE, ENTRY_TYPE), typesWithGap),
            () -> assertEquals(List.of(root.resolve(POSTING)), repaired),
            () -> assertEquals(List.of(), service.getIndexIncompleteFiles()),
            () -> assertEquals(List.of(), service.getWarnings()),
            () -> assertEquals(DECLARED_TYPES, symbolSearchTypes(service.getSearchService())),
            () -> assertEquals(List.of(POSTING_TYPE + ".reverse"), methodDeclarations(service, "reverse")),
            () -> assertEquals(ENTRY_REFERENCES_WITH_REVERSE, references(service, ENTRY_TYPE)));
    }

    @Test
    @DisplayName("a gap on a file the disk-sync repair re-reads, kept from re-indexing, is named by INDEX_INCOMPLETE")
    void editedFileWithGap_keptFromRepair_named() throws Exception {
        Path root = helper.copyFixture(FIXTURE);
        JdtServiceImpl service = new JdtServiceImpl();
        service.setIndexRepairEnabled(false);
        service.loadProject(root);

        editPostingThenInduceGap(service);
        List<Path> repaired = service.ensureFresh();

        assertAll(
            () -> assertEquals(List.of(root.resolve(POSTING)), repaired),
            () -> assertEquals(List.of(POSTING), service.getIndexIncompleteFiles()),
            () -> assertEquals(List.of(indexIncompleteForPosting()), service.getWarnings()),
            () -> assertEquals(Set.of(ACCOUNT_BOOK_TYPE, LEDGER_TYPE, ENTRY_TYPE), symbolSearchTypes(service.getSearchService())));
    }

    // ========== Helpers ==========

    /** The names of the workspace's projects, sorted. */
    private static List<String> workspaceProjects() {
        return Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
            .map(IProject::getName)
            .sorted()
            .toList();
    }

    /**
     * Close {@code unit}, so reading its declarations goes to its file as for a unit not yet
     * open, and delete its file from disk without telling the workspace.
     */
    private static void deleteBeforeReading(ICompilationUnit unit, Path file) throws JavaModelException {
        unit.close();
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The INDEX_INCOMPLETE warning for a gap on Posting.java alone. */
    private static LoadWarning indexIncompleteForPosting() {
        return new LoadWarning(LoadWarning.INDEX_INCOMPLETE,
            "The search index is missing declarations from 1 file: " + POSTING
                + "; search-based results do not include them",
            RELOAD_OR_RESTART);
    }

    /**
     * Write {@link #POSTING_WITH_REVERSE} to Posting.java and refresh its workspace file, so JDT
     * re-indexes it, then remove its entries from the index: the gap the next disk-sync repair
     * meets when it re-reads the file.
     *
     * @return the types the index finds once the gap is in place
     */
    private static Set<String> editPostingThenInduceGap(JdtServiceImpl service) throws Exception {
        ICompilationUnit posting = unit(service.getJavaProject(), "Posting.java");
        Path location = Path.of(posting.getResource().getLocation().toOSString());
        Files.writeString(location, POSTING_WITH_REVERSE);
        posting.getResource().refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
        induceGap(posting, GapShape.REMOVED);
        return symbolSearchTypes(service.getSearchService());
    }

    /** How {@link #induceGap} leaves a document in the index. */
    private enum GapShape {
        /** None of its entries remain. */
        REMOVED,
        /** Only the entries JDT writes for Ledger's lambda remain: its pseudo-type and method. */
        LAMBDA_ENTRIES_ONLY
    }

    /**
     * Once the indexer is idle, remove {@code unit}'s document from its project's index under
     * the index's write lock, as JDT's RemoveFromIndex job does, and for
     * {@link GapShape#LAMBDA_ENTRIES_ONLY} write back the lambda entries JDT's SourceIndexer
     * records in its resolved pass.
     */
    private static void induceGap(ICompilationUnit unit, GapShape shape) throws CoreException {
        waitForIndexer();
        Index index = projectIndex(unit);
        String document = index.containerRelativePath(unit.getPath().toString());
        ReadWriteMonitor monitor = index.monitor;
        monitor.enterWrite();
        try {
            index.remove(document);
            if (shape == GapShape.LAMBDA_ENTRIES_ONLY) {
                index.addIndexEntry(IIndexConstants.METHOD_DECL, LAMBDA_METHOD_KEY, document);
                index.addIndexEntry(IIndexConstants.TYPE_DECL, LAMBDA_TYPE_KEY, document);
            }
        } finally {
            monitor.exitWrite();
        }
    }

    /** The typeDecl keys the index holds for {@code unit}'s document, in index order. */
    private static List<String> typeDeclarationKeys(ICompilationUnit unit) throws IOException {
        Index index = projectIndex(unit);
        String document = index.containerRelativePath(unit.getPath().toString());
        ReadWriteMonitor monitor = index.monitor;
        monitor.enterRead();
        try {
            List<String> keys = new ArrayList<>();
            EntryResult[] entries = index.query(new char[][] { IIndexConstants.TYPE_DECL }, null, SearchPattern.R_PREFIX_MATCH);
            if (entries != null) {
                for (EntryResult entry : entries) {
                    if (Arrays.asList(entry.getDocumentNames(index)).contains(document)) {
                        keys.add(new String(entry.getWord()));
                    }
                }
            }
            return keys;
        } finally {
            monitor.exitRead();
        }
    }

    /** The index of {@code unit}'s project, which holds the documents of its source folders. */
    private static Index projectIndex(ICompilationUnit unit) {
        return JavaModelManager.getIndexManager()
            .getIndex(unit.getJavaProject().getProject().getFullPath(), true, false);
    }

    /** Wait until the indexer has run every job queued so far. */
    private static void waitForIndexer() throws CoreException {
        new SearchEngine().searchAllTypeNames(
            null, SearchPattern.R_EXACT_MATCH,
            null, SearchPattern.R_EXACT_MATCH,
            IJavaSearchConstants.TYPE, SearchEngine.createWorkspaceScope(),
            new TypeNameRequestor() {
            },
            IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, new NullProgressMonitor());
    }

    /**
     * An importer that, once the project is configured and indexed, induces a gap of the given
     * shape on each named file and records the types the index then finds: the index the
     * load's own verification meets. With a {@code modelWarning}, the resolved model carries it
     * after the model's own warnings.
     */
    private static final class GapInducingImporter extends ProjectImporter {

        private final Map<String, GapShape> gaps;
        private final LoadWarning modelWarning;
        private Set<String> typesAfterGaps;

        GapInducingImporter(Map<String, GapShape> gaps, LoadWarning modelWarning) {
            this.gaps = gaps;
            this.modelWarning = modelWarning;
        }

        @Override
        public BuildModel resolveModel(Path projectPath) {
            BuildModel model = super.resolveModel(projectPath);
            if (modelWarning == null) {
                return model;
            }
            List<LoadWarning> warnings = new ArrayList<>(model.warnings());
            warnings.add(modelWarning);
            return new BuildModel(model.buildSystem(), model.provenance(), model.modules(), model.classpath(),
                model.processorJars(), model.analysisLevel(), model.inputs(), warnings);
        }

        @Override
        public IJavaProject configureJavaProject(IProject project, Path projectPath, BuildModel model,
                                                 WorkspaceManager workspaceManager) throws CoreException {
            IJavaProject javaProject = super.configureJavaProject(project, projectPath, model, workspaceManager);
            for (Map.Entry<String, GapShape> gap : gaps.entrySet()) {
                induceGap(unit(javaProject, gap.getKey()), gap.getValue());
            }
            typesAfterGaps = symbolSearchTypes(new SearchService(javaProject));
            return javaProject;
        }
    }

    /** A workspace manager that fails to remove the project named {@code projectName}. */
    private static WorkspaceManager refusingToRemove(String projectName) {
        return new WorkspaceManager() {
            @Override
            public boolean removeProject(IProject project) throws CoreException {
                if (project.getName().equals(projectName)) {
                    throw new CoreException(new Status(IStatus.ERROR, "org.javalens.core.tests", REMOVAL_FAILURE));
                }
                return super.removeProject(project);
            }
        };
    }

    /** The compilation units of the project's source folders, in model order. */
    private static List<ICompilationUnit> sourceUnits(IJavaProject project) throws CoreException {
        List<ICompilationUnit> units = new ArrayList<>();
        for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
            for (IJavaElement child : root.getChildren()) {
                if (child instanceof IPackageFragment pkg) {
                    units.addAll(Arrays.asList(pkg.getCompilationUnits()));
                }
            }
        }
        return units;
    }

    /** The project's one compilation unit named {@code fileName}. */
    private static ICompilationUnit unit(IJavaProject project, String fileName) throws CoreException {
        List<ICompilationUnit> named = sourceUnits(project).stream()
            .filter(unit -> unit.getElementName().equals(fileName))
            .toList();
        assertEquals(1, named.size(), () -> "compilation units named " + fileName + ": " + named);
        return named.get(0);
    }

    /** Each unit's file relative to the project root, with '/' separators, sorted. */
    private static List<String> paths(JdtServiceImpl service, List<ICompilationUnit> units) {
        return units.stream()
            .map(unit -> service.getProjectRoot()
                .relativize(Path.of(unit.getResource().getLocation().toOSString()))
                .toString().replace('\\', '/'))
            .sorted()
            .toList();
    }

    /** The qualified names of the types search_symbols finds through {@code searchService} for each of {@link #TYPE_QUERIES}. */
    private static Set<String> symbolSearchTypes(SearchService searchService) throws CoreException {
        Set<String> types = new TreeSet<>();
        for (String query : TYPE_QUERIES) {
            for (SearchMatch match : searchService.searchSymbols(query, IJavaSearchConstants.TYPE, 1000).matches()) {
                types.add(((IType) match.getElement()).getFullyQualifiedName('.'));
            }
        }
        return types;
    }

    /** The methods named {@code name} that symbol search finds, as declaring type and name. */
    private static List<String> methodDeclarations(JdtServiceImpl service, String name) throws CoreException {
        List<String> methods = new ArrayList<>();
        for (SearchMatch match : service.getSearchService().searchSymbols(name, IJavaSearchConstants.METHOD, 1000).matches()) {
            IMethod method = (IMethod) match.getElement();
            methods.add(method.getDeclaringType().getFullyQualifiedName('.') + "." + method.getElementName());
        }
        return methods;
    }

    /** The references the service's reference search finds to {@code typeName}, as file:line (0-based), sorted. */
    private static List<String> references(JdtServiceImpl service, String typeName) throws CoreException {
        IType type = service.getJavaProject().findType(typeName);
        List<String> found = new ArrayList<>();
        for (SearchMatch match : service.getSearchService()
                .findReferences(type, IJavaSearchConstants.REFERENCES, 1000).matches()) {
            IFile file = (IFile) match.getResource();
            String source = JavaCore.createCompilationUnitFrom(file).getSource();
            long line = source.substring(0, match.getOffset()).chars().filter(c -> c == '\n').count();
            found.add(file.getName() + ":" + line);
        }
        found.sort((a, b) -> {
            String[] left = a.split(":");
            String[] right = b.split(":");
            int byFile = left[0].compareTo(right[0]);
            return byFile != 0 ? byFile : Integer.compare(Integer.parseInt(left[1]), Integer.parseInt(right[1]));
        });
        return found;
    }

    /** A file's bytes (as ISO-8859-1, one char per byte) or a directory, with its modification time. */
    private record TreeEntry(String bytes, FileTime lastModified) {
    }

    /** Every file and directory under {@code root}, by path relative to it. */
    private static Map<String, TreeEntry> tree(Path root) throws IOException {
        Map<String, TreeEntry> entries = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                String content = Files.isDirectory(path) ? "<directory>"
                    : new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                entries.put(root.relativize(path).toString().replace('\\', '/'),
                    new TreeEntry(content, Files.getLastModifiedTime(path)));
            }
        }
        return entries;
    }
}
