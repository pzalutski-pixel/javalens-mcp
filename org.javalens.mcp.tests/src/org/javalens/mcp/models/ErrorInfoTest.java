package org.javalens.mcp.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ErrorInfo.
 * Tests error code constants and factory methods.
 */
class ErrorInfoTest {

    // ========== Constructor Tests ==========

    @Test
    @DisplayName("constructor should set all fields")
    void constructor_setsAllFields() {
        ErrorInfo error = new ErrorInfo("CODE", "Message", "Hint");

        assertEquals("CODE", error.getCode());
        assertEquals("Message", error.getMessage());
        assertEquals("Hint", error.getHint());
    }

    @Test
    @DisplayName("constructor should allow null hint")
    void constructor_allowsNullHint() {
        ErrorInfo error = new ErrorInfo("CODE", "Message", null);

        assertEquals("CODE", error.getCode());
        assertEquals("Message", error.getMessage());
        assertNull(error.getHint());
    }

    // ========== Factory Method Tests ==========

    @Test
    @DisplayName("projectNotLoaded should have correct code")
    void projectNotLoaded_hasCorrectCode() {
        ErrorInfo error = ErrorInfo.projectNotLoaded();

        assertEquals(ErrorInfo.PROJECT_NOT_LOADED, error.getCode());
        assertNotNull(error.getMessage());
        assertNotNull(error.getHint());
        assertTrue(error.getHint().contains("load_project"));
    }

    @Test
    @DisplayName("fileNotFound should include path in message")
    void fileNotFound_includesPath() {
        String path = "/some/path/File.java";

        ErrorInfo error = ErrorInfo.fileNotFound(path);

        assertEquals(ErrorInfo.FILE_NOT_FOUND, error.getCode());
        assertTrue(error.getMessage().contains(path));
    }

    @Test
    @DisplayName("symbolNotFound should include symbol in message")
    void symbolNotFound_includesSymbol() {
        String symbol = "com.example.MyClass";

        ErrorInfo error = ErrorInfo.symbolNotFound(symbol);

        assertEquals(ErrorInfo.SYMBOL_NOT_FOUND, error.getCode());
        assertTrue(error.getMessage().contains(symbol));
        assertTrue(error.getHint().contains("search_symbols"));
    }

    @Test
    @DisplayName("invalidCoordinates should have zero-based hint")
    void invalidCoordinates_hasZeroBasedHint() {
        ErrorInfo error = ErrorInfo.invalidCoordinates(10, 5, "out of bounds");

        assertEquals(ErrorInfo.INVALID_COORDINATES, error.getCode());
        assertTrue(error.getMessage().contains("10"));
        assertTrue(error.getMessage().contains("5"));
        assertTrue(error.getHint().contains("zero-based"));
    }

    @Test
    @DisplayName("invalidParameter should include param and reason")
    void invalidParameter_includesParamAndReason() {
        ErrorInfo error = ErrorInfo.invalidParameter("filePath", "cannot be empty");

        assertEquals(ErrorInfo.INVALID_PARAMETER, error.getCode());
        assertTrue(error.getMessage().contains("filePath"));
        assertTrue(error.getMessage().contains("cannot be empty"));
    }

    @Test
    @DisplayName("timeout should include operation and duration")
    void timeout_includesOperationAndDuration() {
        ErrorInfo error = ErrorInfo.timeout("search", 30);

        assertEquals(ErrorInfo.TIMEOUT, error.getCode());
        assertTrue(error.getMessage().contains("search"));
        assertTrue(error.getMessage().contains("30"));
    }

    @Test
    @DisplayName("securityViolation should include reason")
    void securityViolation_includesReason() {
        ErrorInfo error = ErrorInfo.securityViolation("path outside project");

        assertEquals(ErrorInfo.SECURITY_VIOLATION, error.getCode());
        assertTrue(error.getMessage().contains("path outside project"));
    }

    @Test
    @DisplayName("internalError should include message")
    void internalError_includesMessage() {
        ErrorInfo error = ErrorInfo.internalError("unexpected null pointer");

        assertEquals(ErrorInfo.INTERNAL_ERROR, error.getCode());
        assertTrue(error.getMessage().contains("unexpected null pointer"));
        assertTrue(error.getHint().contains("bug"));
    }

    @Test
    @DisplayName("refactoringFailed should include reason")
    void refactoringFailed_includesReason() {
        ErrorInfo error = ErrorInfo.refactoringFailed("name conflict");

        assertEquals(ErrorInfo.REFACTORING_FAILED, error.getCode());
        assertTrue(error.getMessage().contains("name conflict"));
    }

    // ========== fromThrowable Tests ==========

    @Test
    @DisplayName("fromThrowable(null) returns a generic internal error")
    void fromThrowable_nullThrowable_isHandled() {
        ErrorInfo error = ErrorInfo.fromThrowable(null);
        assertEquals(ErrorInfo.INTERNAL_ERROR, error.getCode());
        assertTrue(error.getMessage().contains("null exception"));
    }

    @Test
    @DisplayName("fromThrowable(RuntimeException) falls back to plain internalError message")
    void fromThrowable_runtimeException_fallsBackToPlainMessage() {
        ErrorInfo error = ErrorInfo.fromThrowable(new RuntimeException("plain bug"));
        assertEquals(ErrorInfo.INTERNAL_ERROR, error.getCode());
        assertTrue(error.getMessage().contains("plain bug"));
    }

    @Test
    @DisplayName("fromThrowable(CoreException with IStatus) unpacks plugin + code + severity")
    void fromThrowable_coreException_unpacksStatus() {
        org.eclipse.core.runtime.Status status = new org.eclipse.core.runtime.Status(
            org.eclipse.core.runtime.IStatus.ERROR,
            "org.example.test.plugin",
            42,
            "JDT subsystem failure detail",
            null);
        org.eclipse.core.runtime.CoreException ex = new org.eclipse.core.runtime.CoreException(status);

        ErrorInfo error = ErrorInfo.fromThrowable(ex);

        assertEquals(ErrorInfo.INTERNAL_ERROR, error.getCode());
        assertTrue(error.getMessage().contains("org.example.test.plugin"),
            "Message must include plugin id; got: " + error.getMessage());
        assertTrue(error.getMessage().contains("42"),
            "Message must include status code; got: " + error.getMessage());
        assertTrue(error.getMessage().contains("ERROR"),
            "Message must include severity name; got: " + error.getMessage());
        assertTrue(error.getMessage().contains("JDT subsystem failure detail"),
            "Message must include status message; got: " + error.getMessage());
    }

    // Note: ErrorInfo.fromThrowable has two defensive branches that are unreachable
    // through the public Eclipse API:
    //   (a) `if (status != null)` — CoreException's constructor itself NPEs on null
    //       status, so a CoreException with null status can't be constructed.
    //   (b) `status.getPlugin() == null ? "<unknown>" : ...` — Status's constructor
    //       throws IllegalArgumentException on null plugin id.
    // Both source-level guards are belt-and-suspenders; testing them would need
    // reflection or a future Eclipse API breakage. Documented dead-code-defense.

    @Test
    @DisplayName("fromThrowable surfaces WARNING and CANCEL severities by name")
    void fromThrowable_warningAndCancelSeverity_emittedByName() {
        // severityName's switch covers OK/INFO/WARNING/ERROR/CANCEL + default. Only ERROR
        // was tested. Pin WARNING + CANCEL to cover two more branches.
        org.eclipse.core.runtime.Status warningStatus = new org.eclipse.core.runtime.Status(
            org.eclipse.core.runtime.IStatus.WARNING, "test.plugin", 0, "warned", null);
        ErrorInfo warningError = ErrorInfo.fromThrowable(
            new org.eclipse.core.runtime.CoreException(warningStatus));
        assertTrue(warningError.getMessage().contains("WARNING"),
            "WARNING severity must surface by name; got: " + warningError.getMessage());

        org.eclipse.core.runtime.Status cancelStatus = new org.eclipse.core.runtime.Status(
            org.eclipse.core.runtime.IStatus.CANCEL, "test.plugin", 0, "cancelled", null);
        ErrorInfo cancelError = ErrorInfo.fromThrowable(
            new org.eclipse.core.runtime.CoreException(cancelStatus));
        assertTrue(cancelError.getMessage().contains("CANCEL"),
            "CANCEL severity must surface by name; got: " + cancelError.getMessage());
    }

    @Test
    @DisplayName("invalidParameter factory builds with null hint (documented behavior)")
    void invalidParameter_nullHint() {
        // Source line 82 sets hint=null for invalidParameter. Tools relying on hint!=null
        // for OTHER codes must NOT depend on it here. Pinning the null-hint behavior.
        ErrorInfo error = ErrorInfo.invalidParameter("p", "reason");
        assertNull(error.getHint(),
            "invalidParameter factory must produce null hint per its documented contract");
    }

    @Test
    @DisplayName("fromThrowable surfaces OK and INFO severities by name (completing the switch)")
    void fromThrowable_okAndInfoSeverity_emittedByName() {
        // severityName's switch has 5 named branches + 1 default. ERROR/WARNING/CANCEL
        // were already pinned; OK + INFO are the remaining named branches that complete
        // coverage of the documented severity vocabulary.
        org.eclipse.core.runtime.Status okStatus = new org.eclipse.core.runtime.Status(
            org.eclipse.core.runtime.IStatus.OK, "t", 0, "ok-detail", null);
        ErrorInfo okError = ErrorInfo.fromThrowable(
            new org.eclipse.core.runtime.CoreException(okStatus));
        assertTrue(okError.getMessage().contains("OK"),
            "OK severity must surface by name; got: " + okError.getMessage());

        org.eclipse.core.runtime.Status infoStatus = new org.eclipse.core.runtime.Status(
            org.eclipse.core.runtime.IStatus.INFO, "t", 0, "info-detail", null);
        ErrorInfo infoError = ErrorInfo.fromThrowable(
            new org.eclipse.core.runtime.CoreException(infoStatus));
        assertTrue(infoError.getMessage().contains("INFO"),
            "INFO severity must surface by name; got: " + infoError.getMessage());
    }

    @Test
    @DisplayName("fromThrowable default-branch severity emits 'SEVERITY(N)' format")
    void fromThrowable_unknownSeverity_emitsNumeric() {
        // severityName's default branch produces "SEVERITY(N)" for unknown numeric
        // severities. Eclipse defines OK=0/INFO=1/WARNING=2/ERROR=4/CANCEL=8 — any
        // other value falls through to the default. Use a custom Status subclass
        // (IStatus is an interface) to fake severity=99.
        org.eclipse.core.runtime.IStatus weird = new org.eclipse.core.runtime.IStatus() {
            @Override public int getSeverity() { return 99; }
            @Override public boolean isOK() { return false; }
            @Override public boolean matches(int severityMask) { return false; }
            @Override public boolean isMultiStatus() { return false; }
            @Override public org.eclipse.core.runtime.IStatus[] getChildren() {
                return new org.eclipse.core.runtime.IStatus[0];
            }
            @Override public String getPlugin() { return "t"; }
            @Override public int getCode() { return 0; }
            @Override public String getMessage() { return "weird"; }
            @Override public Throwable getException() { return null; }
        };
        ErrorInfo error = ErrorInfo.fromThrowable(
            new org.eclipse.core.runtime.CoreException(weird));
        assertTrue(error.getMessage().contains("SEVERITY(99)"),
            "Unknown severity must surface as 'SEVERITY(99)'; got: " + error.getMessage());
    }

    // ========== Error Code Constants Tests ==========

    @Test
    @DisplayName("error code constants should be defined")
    void errorCodeConstants_areDefined() {
        assertEquals("PROJECT_NOT_LOADED", ErrorInfo.PROJECT_NOT_LOADED);
        assertEquals("FILE_NOT_FOUND", ErrorInfo.FILE_NOT_FOUND);
        assertEquals("SYMBOL_NOT_FOUND", ErrorInfo.SYMBOL_NOT_FOUND);
        assertEquals("INVALID_COORDINATES", ErrorInfo.INVALID_COORDINATES);
        assertEquals("INVALID_PARAMETER", ErrorInfo.INVALID_PARAMETER);
        assertEquals("SECURITY_VIOLATION", ErrorInfo.SECURITY_VIOLATION);
        assertEquals("TIMEOUT", ErrorInfo.TIMEOUT);
        assertEquals("INTERNAL_ERROR", ErrorInfo.INTERNAL_ERROR);
        assertEquals("REFACTORING_FAILED", ErrorInfo.REFACTORING_FAILED);
    }
}
