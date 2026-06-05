package org.javalens.core.project;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstallType;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.VMStandin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Ensures a JDT {@link IVMInstall} backing the project's JRE container exists
 * for the running JVM. Issue #18 surfaces a class of environments where JDT's
 * default-JRE auto-detection ({@code JavaRuntime.detectEclipseRuntime} reading
 * {@code java.home}) doesn't fire — leaving the JRE container unbacked and
 * every source file producing BUILDPATH cascades because {@code java.lang.Object}
 * is unreachable.
 *
 * <p>This helper makes the registration explicit and idempotent. It is called
 * from {@code ProjectImporter.configureJavaProject} before the JRE container
 * entry is added to the project's raw classpath, so the container always has
 * a backing {@link IVMInstall} regardless of whether JDT's fallback succeeds.
 *
 * <p>The IVMInstall id is derived deterministically from {@code java.home} so
 * repeated calls produce at most one registration per JDK location, regardless
 * of how many JavaLens sessions run. JDT persists IVMInstall registrations
 * inside the OSGi {@code -data} workspace; the persistence is therefore
 * confined to the same workspace that holds every other piece of JavaLens
 * project metadata.
 */
public final class JreInstallEnsurer {

    private static final Logger log = LoggerFactory.getLogger(JreInstallEnsurer.class);

    private static final String ID_PREFIX = "javalens-running-jvm-";

    private JreInstallEnsurer() {}

    /**
     * Find or create the {@link IVMInstall} for the running JVM and set it as
     * the JDT default. Returns the install on success; returns {@code null}
     * when the environment doesn't expose a usable {@code java.home} (which
     * should never happen under normal launch — included as a defensive
     * fallback so the caller can surface a clear warning).
     */
    public static IVMInstall ensureRunningJvmRegistered() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            log.warn("Cannot ensure JRE registration: java.home is unset or blank");
            return null;
        }
        File javaHomeFile = new File(javaHome);
        if (!javaHomeFile.isDirectory()) {
            log.warn("Cannot ensure JRE registration: java.home does not point at a directory: {}",
                javaHome);
            return null;
        }

        IVMInstallType standardType = findStandardVmType();
        if (standardType == null) {
            log.warn("Cannot ensure JRE registration: StandardVMType is not registered");
            return null;
        }

        // Find an existing install at the same location — match on canonical
        // path so SDKMAN-style symlinks and equivalent paths don't produce
        // duplicate registrations.
        File canonicalJavaHome = canonicalize(javaHomeFile);
        for (IVMInstall existing : standardType.getVMInstalls()) {
            if (existing.getInstallLocation() == null) continue;
            if (canonicalize(existing.getInstallLocation()).equals(canonicalJavaHome)) {
                ensureDefault(existing);
                return existing;
            }
        }

        // Construct deterministic id from the resolved java.home so the same
        // JDK location always maps to the same registry entry. If an entry
        // with this id already exists at a DIFFERENT location (user moved
        // the JDK, or a previous launch's java.home hashed to the same key),
        // dispose it before re-creating with the current location.
        String id = ID_PREFIX + Integer.toHexString(canonicalJavaHome.getPath().hashCode());
        IVMInstall stale = standardType.findVMInstall(id);
        if (stale != null) {
            standardType.disposeVMInstall(id);
        }

        VMStandin standin = new VMStandin(standardType, id);
        standin.setInstallLocation(javaHomeFile);
        standin.setName(deriveName(javaHomeFile));
        IVMInstall install = standin.convertToRealVM();
        ensureDefault(install);
        return install;
    }

    private static IVMInstallType findStandardVmType() {
        // JDT registers the standard VM type with this id at the launching
        // bundle's activation. The constant lives inside internal launching
        // packages we can't import, so we look up by id literally.
        IVMInstallType byId = JavaRuntime.getVMInstallType(
            "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType");
        if (byId != null) return byId;
        // Fallback for JDT distributions that use the alternate id.
        for (IVMInstallType type : JavaRuntime.getVMInstallTypes()) {
            if (type.getId().contains("StandardVMType")) return type;
        }
        return null;
    }

    private static void ensureDefault(IVMInstall install) {
        // setDefaultVMInstall records the default VM and persists the registry to
        // PREF_VM_XML, so calling it unconditionally makes the registration durable
        // rather than in-memory only. The current default is intentionally not read
        // first: getDefaultVMInstall() re-runs VM detection, and disposes the
        // registry, whenever the persisted default does not resolve.
        try {
            JavaRuntime.setDefaultVMInstall(install, new NullProgressMonitor());
        } catch (CoreException e) {
            log.warn("Could not set default IVMInstall to {}: {}", install.getId(), e.getMessage());
        }
    }

    private static File canonicalize(File f) {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f.getAbsoluteFile();
        }
    }

    private static String deriveName(File javaHomeFile) {
        return "JavaLens detected JRE (" + javaHomeFile.getName() + ")";
    }
}
