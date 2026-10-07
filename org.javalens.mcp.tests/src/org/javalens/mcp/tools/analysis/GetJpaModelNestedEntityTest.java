package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.GetJpaModelTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * get_jpa_model names an entity, and the target of a relationship, by its fully qualified name
 * with {@code $} between nested types, as every tool names a type: an entity declared as a
 * member class of {@code com.fw.Shop} is {@code com.fw.Shop$Shelf}.
 */
class GetJpaModelNestedEntityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /**
     * Shop.java in package com.fw: the class Shop (line 9) with two member entity classes,
     * Shelf (line 12, an id and a List of Item mapped by shelf) and Item (line 22, an id, a
     * Shelf and an Order, the top-level entity of framework-maven).
     */
    private static final String SHOP = """
        package com.fw;

        import jakarta.persistence.Entity;
        import jakarta.persistence.Id;
        import jakarta.persistence.ManyToOne;
        import jakarta.persistence.OneToMany;

        import java.util.List;

        public class Shop {

            @Entity
            public static class Shelf {

                @Id
                private Long id;

                @OneToMany(mappedBy = "shelf")
                private List<Item> items;
            }

            @Entity
            public static class Item {

                @Id
                private Long id;

                @ManyToOne
                private Shelf shelf;

                @ManyToOne
                private Order order;
            }
        }
        """;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("get_jpa_model names the member entities com.fw.Shop$Item and com.fw.Shop$Shelf and the relationships between them with $")
    void nestedEntities() throws Exception {
        Path root = helper.copyFixture("framework-maven");
        Files.writeString(root.resolve("src/main/java/com/fw/Shop.java"), SHOP, StandardCharsets.UTF_8);
        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(root);

        // Entities in name order; framework-maven's own entities Customer and Order first.
        List<Map<String, Object>> entities = List.of(
            entity("com.fw.Customer", "customers",
                List.of(relationship("orders", "OneToMany", "com.fw.Order", "customer")),
                "src/main/java/com/fw/Customer.java", 11),
            entity("com.fw.Order", null,
                List.of(relationship("customer", "ManyToOne", "com.fw.Customer", null)),
                "src/main/java/com/fw/Order.java", 7),
            entity("com.fw.Shop$Item", null,
                List.of(
                    relationship("shelf", "ManyToOne", "com.fw.Shop$Shelf", null),
                    relationship("order", "ManyToOne", "com.fw.Order", null)),
                "src/main/java/com/fw/Shop.java", 22),
            entity("com.fw.Shop$Shelf", null,
                List.of(relationship("items", "OneToMany", "com.fw.Shop$Item", "shelf")),
                "src/main/java/com/fw/Shop.java", 12));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entityCount", 4);
        data.put("entities", entities);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalCount", 4);
        meta.put("returnedCount", 4);
        meta.put("truncated", false);
        meta.put("suggestedNextTools", List.of(
            "analyze_type for full details of an entity",
            "find_references to see where an entity is used"));
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("success", true);
        expected.put("data", data);
        expected.put("meta", meta);

        ObjectNode args = mapper.createObjectNode();
        String direct = mapper.writeValueAsString(new GetJpaModelTool(() -> service).execute(args.deepCopy()));
        assertAll(
            () -> assertEquals(mapper.valueToTree(expected), mapper.readTree(direct), "execute() payload"),
            () -> assertEquals(mapper.valueToTree(expected),
                new EnvelopeHarness(service).assertEnvelopeFidelity("get_jpa_model", args), "envelope payload"));
    }

    private static Map<String, Object> entity(String name, String table, List<Map<String, Object>> relationships,
                                              String filePath, int line) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("name", name);
        if (table != null) {
            entity.put("table", table);
        }
        entity.put("idField", "id");
        entity.put("relationships", relationships);
        entity.put("filePath", filePath);
        entity.put("line", line);
        return entity;
    }

    private static Map<String, Object> relationship(String field, String kind, String target, String mappedBy) {
        Map<String, Object> relationship = new LinkedHashMap<>();
        relationship.put("field", field);
        relationship.put("kind", kind);
        relationship.put("target", target);
        if (mappedBy != null) {
            relationship.put("mappedBy", mappedBy);
        }
        return relationship;
    }
}
