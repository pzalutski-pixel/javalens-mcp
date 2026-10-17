package org.javalens.core.project;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What JavaLens reads from one Maven project element: an effective POM project, which Maven
 * has interpolated and merged with its parents, or a raw {@code pom.xml}, whose values may
 * still hold {@code ${...}} references. Each list keeps document order; a value the element
 * does not declare is null or empty.
 *
 * @param groupId the project's groupId, or its parent's when it declares none
 * @param artifactId the project's artifactId
 * @param version the project's version, or its parent's when it declares none
 * @param modules the {@code <modules>} entries, as written
 * @param profileModules the {@code <modules>} entries of each profile, by profile id
 * @param properties the {@code <properties>}, by name
 * @param buildDirectory {@code build/directory}
 * @param sourceDirectory {@code build/sourceDirectory}
 * @param testSourceDirectory {@code build/testSourceDirectory}
 * @param outputDirectory {@code build/outputDirectory}
 * @param testOutputDirectory {@code build/testOutputDirectory}
 * @param compilerRelease the maven-compiler-plugin's configured {@code release}
 * @param compilerSource the maven-compiler-plugin's configured {@code source}
 * @param processorPaths the maven-compiler-plugin's {@code annotationProcessorPaths}
 * @param mainSources build-helper {@code add-source} and Kotlin main source directories
 * @param testSources build-helper {@code add-test-source} and Kotlin test source directories
 * @param managedVersions {@code dependencyManagement} versions, by {@code groupId:artifactId}
 * @param parentRelativePath the parent's {@code relativePath}, or null when the pom names no parent
 */
record MavenPom(String groupId, String artifactId, String version, List<String> modules,
                Map<String, List<String>> profileModules, Map<String, String> properties,
                String buildDirectory, String sourceDirectory, String testSourceDirectory,
                String outputDirectory, String testOutputDirectory,
                String compilerRelease, String compilerSource, List<Coordinates> processorPaths,
                List<String> mainSources, List<String> testSources,
                Map<String, String> managedVersions, String parentRelativePath) {

    static final String COMPILER_PLUGIN = "maven-compiler-plugin";
    static final String BUILD_HELPER_PLUGIN = "build-helper-maven-plugin";
    static final String KOTLIN_PLUGIN = "kotlin-maven-plugin";

    /** Maven coordinates; {@code version} may be null when the declaration leaves it out. */
    record Coordinates(String groupId, String artifactId, String version) {
    }

    /** {@code groupId:artifactId}, the module id. */
    String id() {
        return groupId + ":" + artifactId;
    }

    /** Read the project element {@code project}. */
    static MavenPom of(Element project) {
        Optional<Element> parent = SecureXml.child(project, "parent");
        String groupId = SecureXml.text(project, "groupId")
            .orElse(parent.flatMap(p -> SecureXml.text(p, "groupId")).orElse(null));
        String version = SecureXml.text(project, "version")
            .orElse(parent.flatMap(p -> SecureXml.text(p, "version")).orElse(null));
        String artifactId = SecureXml.text(project, "artifactId").orElse(null);
        String parentRelativePath = parent.isEmpty() ? null
            : SecureXml.child(parent.get(), "relativePath")
                .map(element -> element.getTextContent().trim())
                .orElse("../pom.xml");

        List<String> modules = SecureXml.child(project, "modules")
            .map(element -> SecureXml.texts(element, "module")).orElse(List.of());
        Map<String, List<String>> profileModules = new LinkedHashMap<>();
        SecureXml.child(project, "profiles").ifPresent(profiles -> {
            for (Element profile : SecureXml.children(profiles, "profile")) {
                List<String> declared = SecureXml.child(profile, "modules")
                    .map(element -> SecureXml.texts(element, "module")).orElse(List.of());
                if (!declared.isEmpty()) {
                    profileModules.put(SecureXml.text(profile, "id").orElse("(no id)"), declared);
                }
            }
        });

        Map<String, String> properties = new LinkedHashMap<>();
        SecureXml.child(project, "properties").ifPresent(element -> {
            for (Element property : SecureXml.children(element)) {
                properties.put(SecureXml.localName(property), property.getTextContent().trim());
            }
        });

        Map<String, String> managedVersions = new LinkedHashMap<>();
        SecureXml.path(project, "dependencyManagement", "dependencies").ifPresent(dependencies -> {
            for (Element dependency : SecureXml.children(dependencies, "dependency")) {
                Optional<String> g = SecureXml.text(dependency, "groupId");
                Optional<String> a = SecureXml.text(dependency, "artifactId");
                Optional<String> v = SecureXml.text(dependency, "version");
                if (g.isPresent() && a.isPresent() && v.isPresent()) {
                    managedVersions.putIfAbsent(g.get() + ":" + a.get(), v.get());
                }
            }
        });

        Optional<Element> build = SecureXml.child(project, "build");
        String compilerRelease = null;
        String compilerSource = null;
        List<Coordinates> processorPaths = new ArrayList<>();
        List<String> mainSources = new ArrayList<>();
        List<String> testSources = new ArrayList<>();
        // A raw pom often configures the compiler plugin in pluginManagement only; Maven applies
        // that configuration to the plugin it binds by default. An effective pom has already
        // merged it into build/plugins, which is read first.
        List<Element> compilerPlugins = new ArrayList<>(plugins(build));
        compilerPlugins.addAll(build.flatMap(b -> SecureXml.path(b, "pluginManagement", "plugins"))
            .map(element -> SecureXml.children(element, "plugin")).orElse(List.of()));
        for (Element plugin : compilerPlugins) {
            if (!COMPILER_PLUGIN.equals(SecureXml.text(plugin, "artifactId").orElse(""))) continue;
            Optional<Element> configuration = SecureXml.child(plugin, "configuration");
            if (configuration.isEmpty()) continue;
            if (compilerRelease == null) {
                compilerRelease = SecureXml.text(configuration.get(), "release").orElse(null);
            }
            if (compilerSource == null) {
                compilerSource = SecureXml.text(configuration.get(), "source").orElse(null);
            }
            if (processorPaths.isEmpty()) {
                SecureXml.child(configuration.get(), "annotationProcessorPaths").ifPresent(paths -> {
                    for (Element path : SecureXml.children(paths, "path")) {
                        Optional<String> g = SecureXml.text(path, "groupId");
                        Optional<String> a = SecureXml.text(path, "artifactId");
                        if (g.isPresent() && a.isPresent()) {
                            processorPaths.add(new Coordinates(g.get(), a.get(),
                                SecureXml.text(path, "version").orElse(null)));
                        }
                    }
                });
            }
        }
        for (Element plugin : plugins(build)) {
            String pluginArtifact = SecureXml.text(plugin, "artifactId").orElse("");
            Optional<Element> configuration = SecureXml.child(plugin, "configuration");
            switch (pluginArtifact) {
                case BUILD_HELPER_PLUGIN -> {
                    for (Element execution : executions(plugin)) {
                        List<String> goals = SecureXml.child(execution, "goals")
                            .map(element -> SecureXml.texts(element, "goal")).orElse(List.of());
                        List<String> sources = SecureXml.path(execution, "configuration", "sources")
                            .map(element -> SecureXml.texts(element, "source")).orElse(List.of());
                        if (goals.contains("add-source")) mainSources.addAll(sources);
                        if (goals.contains("add-test-source")) testSources.addAll(sources);
                    }
                }
                case KOTLIN_PLUGIN -> {
                    configuration.flatMap(element -> SecureXml.child(element, "sourceDirs"))
                        .ifPresent(element -> mainSources.addAll(SecureXml.texts(element, "sourceDir")));
                    for (Element execution : executions(plugin)) {
                        List<String> goals = SecureXml.child(execution, "goals")
                            .map(element -> SecureXml.texts(element, "goal")).orElse(List.of());
                        List<String> dirs = SecureXml.path(execution, "configuration", "sourceDirs")
                            .map(element -> SecureXml.texts(element, "sourceDir")).orElse(List.of());
                        if (goals.contains("compile")) mainSources.addAll(dirs);
                        if (goals.contains("test-compile")) testSources.addAll(dirs);
                    }
                }
                default -> { }
            }
        }

        return new MavenPom(groupId, artifactId, version, modules, profileModules, properties,
            build.flatMap(b -> SecureXml.text(b, "directory")).orElse(null),
            build.flatMap(b -> SecureXml.text(b, "sourceDirectory")).orElse(null),
            build.flatMap(b -> SecureXml.text(b, "testSourceDirectory")).orElse(null),
            build.flatMap(b -> SecureXml.text(b, "outputDirectory")).orElse(null),
            build.flatMap(b -> SecureXml.text(b, "testOutputDirectory")).orElse(null),
            compilerRelease, compilerSource, List.copyOf(processorPaths),
            List.copyOf(mainSources), List.copyOf(testSources), managedVersions, parentRelativePath);
    }

    /**
     * This pom with what a static read takes from its parent filled in: the parent's
     * properties under its own, the parent's groupId and version where it declares none, the
     * parent's managed versions under its own, and the parent's compiler release, source and
     * processor paths where it configures none.
     */
    MavenPom inheriting(MavenPom parent) {
        Map<String, String> mergedProperties = new LinkedHashMap<>(parent.properties());
        mergedProperties.putAll(properties);
        Map<String, String> mergedManaged = new LinkedHashMap<>(parent.managedVersions());
        mergedManaged.putAll(managedVersions);
        return new MavenPom(groupId != null ? groupId : parent.groupId(), artifactId,
            version != null ? version : parent.version(), modules, profileModules, mergedProperties,
            buildDirectory, sourceDirectory, testSourceDirectory, outputDirectory, testOutputDirectory,
            compilerRelease != null ? compilerRelease : parent.compilerRelease(),
            compilerSource != null ? compilerSource : parent.compilerSource(),
            processorPaths.isEmpty() ? parent.processorPaths() : processorPaths,
            mainSources, testSources, mergedManaged, parentRelativePath);
    }

    /** This pom with {@code resolve} applied to every value that may hold a {@code ${...}} reference. */
    MavenPom resolved(java.util.function.UnaryOperator<String> resolve) {
        Map<String, String> resolvedProperties = new LinkedHashMap<>();
        properties.forEach((name, value) -> resolvedProperties.put(name, resolve.apply(value)));
        Map<String, String> resolvedManaged = new LinkedHashMap<>();
        managedVersions.forEach((key, value) -> resolvedManaged.put(key, resolve.apply(value)));
        return new MavenPom(apply(resolve, groupId), artifactId, apply(resolve, version),
            modules.stream().map(resolve).toList(), profileModules, resolvedProperties,
            apply(resolve, buildDirectory), apply(resolve, sourceDirectory), apply(resolve, testSourceDirectory),
            apply(resolve, outputDirectory), apply(resolve, testOutputDirectory),
            apply(resolve, compilerRelease), apply(resolve, compilerSource),
            processorPaths.stream().map(path -> new Coordinates(apply(resolve, path.groupId()),
                apply(resolve, path.artifactId()), apply(resolve, path.version()))).toList(),
            mainSources.stream().map(resolve).toList(), testSources.stream().map(resolve).toList(),
            resolvedManaged, parentRelativePath);
    }

    private static String apply(java.util.function.UnaryOperator<String> resolve, String value) {
        return value == null ? null : resolve.apply(value);
    }

    /**
     * The level maven-compiler-plugin compiles at, as the plugin chooses it: {@code release}
     * when set, from the plugin's configuration or else the {@code maven.compiler.release}
     * property; otherwise {@code source}, from the configuration or else
     * {@code maven.compiler.source}. A value that is still an unresolved {@code ${...}}
     * reference counts as unset. Null when neither is set.
     */
    String compilerLevel() {
        String release = firstSet(compilerRelease, properties.get("maven.compiler.release"));
        return release != null ? release : firstSet(compilerSource, properties.get("maven.compiler.source"));
    }

    private static String firstSet(String configured, String property) {
        if (isSet(configured)) return configured;
        return isSet(property) ? property : null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank() && !value.contains("${");
    }

    private static List<Element> plugins(Optional<Element> build) {
        return build.flatMap(b -> SecureXml.child(b, "plugins"))
            .map(element -> SecureXml.children(element, "plugin")).orElse(List.of());
    }

    private static List<Element> executions(Element plugin) {
        return SecureXml.child(plugin, "executions")
            .map(element -> SecureXml.children(element, "execution")).orElse(List.of());
    }
}
