package org.example.jickle;

import org.example.additional.Person;
import org.example.jickle.annotation.JicklableClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JickleTest {

    private JickleSerializer serializer;
    private JickleDeserializer deserializer;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        serializer = new JickleSerializer(false);
        deserializer = new JickleDeserializer(false);
        tempDir = Files.createTempDirectory("jickle-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
    }

    @Test
    void roundTripsObjectTreeWithListFieldsAndSharedReferences() throws Exception {
        Person founder = new Person(60, "Founder", null);
        Person manager = new Person(35, "Manager", founder);
        Person devOne = new Person(28, "Dev One", manager);
        Person devTwo = new Person(26, "Dev Two", manager);

        OrgUnit platform = new OrgUnit(
                "Platform",
                manager,
                List.of(manager, devOne, devTwo),
                List.of(),
                Map.of("backend", List.of(devOne, devTwo))
        );

        OrgUnit company = new OrgUnit(
                "Company",
                founder,
                List.of(founder, manager),
                List.of(platform),
                Map.of("leadership", List.of(founder, manager))
        );

        Path file = tempDir.resolve("org-tree.json");
        serializer.dump(company, file.toString());

        OrgUnit restored = (OrgUnit) deserializer.load(file.toString()).get(0);
        OrgUnit restoredPlatform = restored.children.get(0);

        assertEquals("Company", restored.name);
        assertEquals(1, restored.children.size());
        assertEquals("Platform", restoredPlatform.name);
        assertEquals(2, restored.members.size());
        assertEquals(3, restoredPlatform.members.size());
        assertEquals(2, restoredPlatform.squads.get("backend").size());

        assertSame(restored.lead, restored.members.get(0));
        assertSame(restored.members.get(1), restoredPlatform.lead);
        assertSame(restoredPlatform.members.get(1), restoredPlatform.squads.get("backend").get(0));
        assertSame(restoredPlatform.members.get(2), restoredPlatform.squads.get("backend").get(1));
        assertSame(restored.members.get(1), restoredPlatform.members.get(0));
    }

    @Test
    void roundTripsCustomCollectionWithOwnField() throws Exception {
        TaggedPeople original = new TaggedPeople("core-team", List.of(
                new Person(30, "Alice", null),
                new Person(31, "Bob", null)
        ));

        Path file = tempDir.resolve("tagged-people.json");
        serializer.dump(original, file.toString());

        Object restoredObject = deserializer.load(file.toString()).get(0);
        TaggedPeople restored = assertInstanceOf(TaggedPeople.class, restoredObject);

        assertEquals("core-team", restored.label);
        assertEquals(2, restored.size());
        assertEquals("Alice", restored.get(0).name);
        assertEquals("Bob", restored.get(1).name);
    }

    @Test
    void roundTripsArraysAndGenericCollections() throws Exception {
        int[] numbers = {1, 2, 3, 4};
        Set<String> tags = new LinkedHashSet<>(List.of("alpha", "beta"));

        Path numbersFile = tempDir.resolve("numbers.json");
        serializer.dump(numbers, numbersFile.toString());
        int[] restoredNumbers = (int[]) deserializer.load(numbersFile.toString()).get(0);
        assertArrayEquals(numbers, restoredNumbers);

        Path tagsFile = tempDir.resolve("tags.json");
        serializer.dump(tags, tagsFile.toString());
        @SuppressWarnings("unchecked")
        Set<String> restoredTags = (Set<String>) deserializer.load(tagsFile.toString()).get(0);

        assertEquals(tags, restoredTags);
        assertTrue(restoredTags instanceof LinkedHashSet);
    }

    @Test
    void filtersDumpListByNestedListPath() throws Exception {
        Person alice = new Person(30, "Alice", null);
        Person bob = new Person(27, "Bob", alice);
        Person carol = new Person(25, "Carol", null);

        OrgUnit alpha = new OrgUnit("Alpha", alice, List.of(alice, bob), List.of(), Map.of());
        OrgUnit beta = new OrgUnit("Beta", carol, List.of(carol), List.of(), Map.of());

        Path file = tempDir.resolve("filtered.json");
        serializer.dumpList(List.of(alpha, beta), file.toString());

        List<Object> restored = deserializer.load(file.toString(), JickleFilter.eq("members.0.name", "Alice"));
        assertEquals(1, restored.size());

        OrgUnit restoredAlpha = assertInstanceOf(OrgUnit.class, restored.get(0));
        assertEquals("Alpha", restoredAlpha.name);
        assertEquals(2, restoredAlpha.members.size());
        assertSame(restoredAlpha.lead, restoredAlpha.members.get(0));
    }

    @Test
    void keepsExistingJsonMarkersForReferencesAndContainers() throws Exception {
        Person parent = new Person(50, "Parent", null);
        Person child = new Person(20, "Child", parent);
        OrgUnit unit = new OrgUnit("Ops", child, List.of(child), List.of(), Map.of());

        Path file = tempDir.resolve("markers.json");
        serializer.dump(unit, file.toString());

        String json = Files.readString(file);
        assertTrue(json.contains("\"object_lead\":"));
        assertTrue(json.contains("\"object_members\":"));
        assertTrue(json.contains("\"is_container\":true"));
        assertTrue(json.contains("\"collection_class\":\"java.util.ImmutableCollections$List12\"")
                || json.contains("\"collection_class\":\"java.util.ImmutableCollections$ListN\"")
                || json.contains("\"collection_class\":\"java.util.Arrays$ArrayList\""));
    }

    @Test
    void respectsJickleIgnoreAnnotation() throws Exception {
        Person person = new Person(30, "SecretGuy", null);

        Path file = tempDir.resolve("ignore.json");
        serializer.dump(person, file.toString());

        String json = Files.readString(file);
        assertFalse(json.contains("bitcoin_wallet_password"));
    }

    @Test
    void rejectsNonJicklableUserClassWhenUnsafeDisabled() {
        class BadBox {
            List<String> names = List.of("x", "y");
        }

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                serializer.dump(new BadBox(), tempDir.resolve("bad.json").toString())
        );

        assertTrue(exception.getMessage().contains("@JicklableClass"));
    }

    @JicklableClass
    static class OrgUnit {
        public String name;
        public Person lead;
        public List<Person> members;
        public List<OrgUnit> children;
        public Map<String, List<Person>> squads;

        public OrgUnit() {
        }

        OrgUnit(String name, Person lead, List<Person> members, List<OrgUnit> children, Map<String, List<Person>> squads) {
            this.name = name;
            this.lead = lead;
            this.members = members;
            this.children = children;
            this.squads = squads;
        }
    }

    @JicklableClass
    static class TaggedPeople extends ArrayList<Person> {
        public String label;

        public TaggedPeople() {
        }

        TaggedPeople(String label, Collection<Person> people) {
            this.label = label;
            addAll(people);
        }
    }
}
