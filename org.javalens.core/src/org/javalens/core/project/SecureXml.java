package org.javalens.core.project;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads build files as XML with a parser that resolves no external entity and accepts no
 * DOCTYPE, so a build file cannot make the load read another file or the network. Comments
 * are not elements, so nothing in a comment is read as a declaration.
 *
 * <p>Element lookups compare local names, so they work with and without the POM namespace.
 */
final class SecureXml {

    private SecureXml() {
    }

    /** The document element of the XML file at {@code file}. */
    static Element read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            Document document = builder().parse(in);
            return document.getDocumentElement();
        } catch (SAXException e) {
            throw new IOException(file.getFileName() + " is not well-formed XML: " + e.getMessage(), e);
        }
    }

    private static DocumentBuilder builder() throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IOException("No secure XML parser is available: " + e.getMessage(), e);
        }
    }

    /** The child elements of {@code parent} named {@code name}, in document order. */
    static List<Element> children(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(localName(element))) {
                children.add(element);
            }
        }
        return children;
    }

    /** Every child element of {@code parent}, in document order. */
    static List<Element> children(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                children.add(element);
            }
        }
        return children;
    }

    /** The first child element of {@code parent} named {@code name}. */
    static Optional<Element> child(Element parent, String name) {
        List<Element> children = children(parent, name);
        return children.isEmpty() ? Optional.empty() : Optional.of(children.get(0));
    }

    /** The element at {@code path} below {@code parent}, following the first match of each name. */
    static Optional<Element> path(Element parent, String... path) {
        Optional<Element> current = Optional.of(parent);
        for (String name : path) {
            current = current.flatMap(element -> child(element, name));
        }
        return current;
    }

    /** The trimmed text of the element at {@code path} below {@code parent}; empty when absent or blank. */
    static Optional<String> text(Element parent, String... path) {
        return path(parent, path).map(element -> element.getTextContent().trim()).filter(text -> !text.isEmpty());
    }

    /** The trimmed text of each child of {@code parent} named {@code name} that is not blank, in order. */
    static List<String> texts(Element parent, String name) {
        List<String> texts = new ArrayList<>();
        for (Element element : children(parent, name)) {
            String text = element.getTextContent().trim();
            if (!text.isEmpty()) {
                texts.add(text);
            }
        }
        return texts;
    }

    static String localName(Element element) {
        return element.getLocalName() != null ? element.getLocalName() : element.getTagName();
    }
}
