package org.javalens.mcp.rewrite;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.manipulation.JavaManipulation;
import org.eclipse.jdt.internal.core.manipulation.CodeTemplateContextType;
import org.eclipse.jface.text.templates.Template;
import org.eclipse.text.templates.ContextTypeRegistry;
import org.eclipse.text.templates.TemplatePersistenceData;
import org.eclipse.text.templates.TemplateStoreCore;
import org.osgi.service.prefs.Preferences;

/**
 * Seeds the state JDT's manipulation layer needs to run outside the Eclipse
 * IDE: the JavaManipulation preference node (getter/setter naming, code style
 * lookups), the import-rewrite order/thresholds, and the code-template store
 * that stub generation (getter/setter bodies) reads — all things the desktop
 * IDE normally provides. Used by both the clean-up and the refactoring
 * invokers. Idempotent.
 */
public final class HeadlessJdtEnvironment {

    private static volatile boolean ready = false;

    private HeadlessJdtEnvironment() {
    }

    public static void ensure() {
        if (ready) {
            return;
        }
        synchronized (HeadlessJdtEnvironment.class) {
            if (ready) {
                return;
            }
            if (JavaManipulation.getPreferenceNodeId() == null) {
                JavaManipulation.setPreferenceNodeId(JavaManipulation.ID_PLUGIN);
            }
            // The import rewrite used by clean-ups and refactorings reads these;
            // without them it throws on a null import order. Mirror JDT defaults.
            Preferences node = InstanceScope.INSTANCE.getNode(JavaManipulation.ID_PLUGIN);
            putIfAbsent(node, "org.eclipse.jdt.ui.importorder", "java;javax;jakarta;org;com");
            putIfAbsent(node, "org.eclipse.jdt.ui.ondemandthreshold", "99");
            putIfAbsent(node, "org.eclipse.jdt.ui.staticondemandthreshold", "99");

            // Code templates: StubUtility renders generated member bodies from
            // these. Register the standard context types and the default body
            // patterns (the same defaults the IDE ships).
            if (JavaManipulation.getCodeTemplateStore() == null) {
                try {
                    ContextTypeRegistry registry = new ContextTypeRegistry();
                    CodeTemplateContextType.registerContextTypes(registry);
                    JavaManipulation.setCodeTemplateContextRegistry(registry);

                    TemplateStoreCore store = new TemplateStoreCore(registry,
                        InstanceScope.INSTANCE.getNode(JavaManipulation.ID_PLUGIN),
                        "org.eclipse.jdt.ui.text.custom_code_templates");
                    store.load();
                    addTemplate(store, "getterbody", CodeTemplateContextType.GETTERBODY_CONTEXTTYPE,
                        CodeTemplateContextType.GETTERSTUB_ID, "return ${field};");
                    addTemplate(store, "setterbody", CodeTemplateContextType.SETTERBODY_CONTEXTTYPE,
                        CodeTemplateContextType.SETTERSTUB_ID, "${field} = ${param};");
                    addTemplate(store, "methodbody", CodeTemplateContextType.METHODBODY_CONTEXTTYPE,
                        CodeTemplateContextType.METHODSTUB_ID, "${body_statement}");
                    addTemplate(store, "constructorbody", CodeTemplateContextType.CONSTRUCTORBODY_CONTEXTTYPE,
                        CodeTemplateContextType.CONSTRUCTORSTUB_ID, "${body_statement}");
                    JavaManipulation.setCodeTemplateStore(store);
                } catch (Exception e) {
                    throw new IllegalStateException("Could not seed JDT code templates", e);
                }
            }
            ready = true;
        }
    }

    private static void addTemplate(TemplateStoreCore store, String name, String contextType,
                                    String id, String pattern) {
        store.add(new TemplatePersistenceData(
            new Template(name, "", contextType, pattern, false), true, id));
    }

    private static void putIfAbsent(Preferences node, String key, String value) {
        if (node.get(key, null) == null) {
            node.put(key, value);
        }
    }
}
