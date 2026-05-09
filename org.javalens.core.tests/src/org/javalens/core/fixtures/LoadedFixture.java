package org.javalens.core.fixtures;

import org.javalens.core.JdtServiceImpl;

import java.util.Collections;
import java.util.List;

/**
 * Result of loading a test fixture project.
 *
 * <p>Bundles the live {@link JdtServiceImpl} together with a {@link ClasspathSnapshot} captured
 * immediately after load and the {@code code} field of every {@link
 * org.javalens.core.project.model.LoadWarning} the load surfaced.
 *
 * @param warnings warning codes (e.g. {@code MAVEN_SUBPROCESS_FAILED}) emitted during load,
 *                 ordered as they were appended; empty when the load was clean.
 */
public record LoadedFixture(
    JdtServiceImpl service,
    ClasspathSnapshot classpath,
    List<String> warnings
) {
    public LoadedFixture {
        warnings = warnings == null ? Collections.emptyList() : Collections.unmodifiableList(warnings);
    }
}
