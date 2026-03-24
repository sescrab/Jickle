package org.example.jickle;

import org.example.Person;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

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
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
    }

    @Test
    void testPersonWithParentRoundTrip() throws Exception {
        Person python = new Person(69, "True programmer", null);
        Person dimas = new Person(20, "Dimas", python);
        Person orphan = new Person(5, "Orphan", dimas);

        Person[] original = {python, dimas, orphan};

        Path file = tempDir.resolve("family.json");
        serializer.dump(original, file.toString());

        List<Object> result = deserializer.load(file.toString());
        Person[] restored = (Person[]) result.get(0);

        assertArrayEquals(original, restored, "Объекты и ссылки должны быть полностью восстановлены");
        assertSame(restored[0], restored[1].parent, "Ссылки должны указывать на один и тот же объект");
    }

    @Test
    void testImmutableListRoundTrip() throws Exception {
        Person p = new Person(42, "Test", null);
        List<Person> original = List.of(p);

        Path file = tempDir.resolve("list.json");
        serializer.dump(original, file.toString());

        List<Object> result = deserializer.load(file.toString());
        List<?> restored = (List<?>) result.get(0);

        assertEquals(1, restored.size());
        assertEquals(p, restored.get(0));
    }

    @Test
    void testPrimitiveAndObjectArrays() throws Exception {
        int[] ints = {1, 2, 3, 4};
        Person[] persons = {new Person(10, "A", null), new Person(20, "B", null)};

        Path file1 = tempDir.resolve("intarray.json");
        serializer.dump(ints, file1.toString());

        List<Object> r1 = deserializer.load(file1.toString());
        assertArrayEquals(ints, (int[]) r1.get(0));

        Path file2 = tempDir.resolve("personarray.json");
        serializer.dump(persons, file2.toString());

        List<Object> r2 = deserializer.load(file2.toString());
        assertArrayEquals(persons, (Person[]) r2.get(0));
    }

    @Test
    void testHashMapWithMixedValues() throws Exception {
        Person p = new Person(99, "MapUser", null);
        Map<String, Object> original = new HashMap<>();
        original.put("person", p);
        original.put("number", 123);
        original.put("nullValue", null);
        original.put("list", List.of("a", "b"));

        Path file = tempDir.resolve("map.json");
        serializer.dump(original, file.toString());

        List<Object> result = deserializer.load(file.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> restored = (Map<String, Object>) result.get(0);

        assertEquals(original.size(), restored.size());
        assertEquals(p, restored.get("person"));
        assertEquals(123, restored.get("number"));
        assertNull(restored.get("nullValue"));
    }

    @Test
    void testJickleIgnoreAnnotation() throws Exception {
        Person p = new Person(30, "SecretGuy", null);

        Path file = tempDir.resolve("ignore.json");
        serializer.dump(p, file.toString());

        String json = Files.readString(file);
        assertFalse(json.contains("bitcoin_wallet_password"), "Поле с @JickleIgnore не должно попасть в JSON");
    }

    @Test
    void testClassNameForArrayIsHumanReadable() throws Exception {
        Person[] arr = {new Person(1, "Test", null)};

        Path file = tempDir.resolve("array.json");
        serializer.dump(arr, file.toString());

        String json = Files.readString(file);
        assertTrue(json.contains("\"class_name\":\"org.example.Person[]\""),
                "Массивы должны иметь красивое имя, а не [L...");
    }

    @Test
    void testUnsupportedCollectionThrowsClearMessage() {
        Set<String> unsupported = Set.of("unsupported");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> serializer.dump(unsupported, tempDir.resolve("bad.json").toString()));

        assertTrue(ex.getMessage().contains("not supported"));
    }

    @Test
    void testNonJicklableClassThrowsWhenNotAllowUnsafe() {
        class Bad {
            public int x;
        }

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> serializer.dump(new Bad(), tempDir.resolve("bad.json").toString()));

        assertTrue(ex.getMessage().contains("@JicklableClass"));
    }
}