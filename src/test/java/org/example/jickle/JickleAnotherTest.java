package org.example.jickle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.jickle.annotation.JicklableClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JickleAnotherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JickleSerializer serializer;
    private JickleDeserializer deserializer;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        serializer = new JickleSerializer(false);
        deserializer = new JickleDeserializer(false);
        tempDir = Files.createTempDirectory("jickle-comprehensive-test");
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

    @JicklableClass
    static class Person {
        public String name;
        public int age;
        public Address address;
        public List<String> tags = new ArrayList<>();

        public Person() {}
    }

    @JicklableClass
    static class Address {
        public String city;
        public String street;

        public Address() {}
    }

    @JicklableClass
    static class Node {
        public String value;
        public List<Node> children = new ArrayList<>();
        public Node parent;

        public Node() {}
    }

    @JicklableClass
    static class ContainerHolder {
        public List<Integer> list = new ArrayList<>();
        public Set<String> set = new LinkedHashSet<>();
        public Map<String, Person> map = new LinkedHashMap<>();
        public Queue<String> queue = new LinkedList<>();
        public Deque<Double> deque = new ArrayDeque<>();
        public int[] intArray;

        public ContainerHolder() {}
    }

    @JicklableClass
    enum Color { RED, GREEN, BLUE }

    @JicklableClass
    static class EnumHolder {
        public Color color;
        public List<Color> colors = new ArrayList<>();

        public EnumHolder() {}
    }

    @JicklableClass
    static class MixedArrayHolder {
        public Object[] mixed;

        public MixedArrayHolder() {}
    }

    @JicklableClass
    static class PrimitiveHolder {
        public boolean bool;
        public byte b;
        public short s;
        public char c;
        public int i;
        public long l;
        public float f;
        public double d;
        public String str;

        public PrimitiveHolder() {}
    }

    @JicklableClass
    static class NestedHolder {
        public List<List<Integer>> listOfLists = new ArrayList<>();
        public String[][] arrayOfArrays;

        public NestedHolder() {}
    }

    @JicklableClass
    static class UnsafeNested {
        public String value;
        public Inner inner;

        public UnsafeNested() {}

        static class Inner {
            public int number;

            public Inner() {}
        }
    }

    @Test
    void roundTripsSimplePerson() throws Exception {
        Person original = new Person();
        original.name = "Alice";
        original.age = 30;
        original.tags.add("developer");

        Path file = tempDir.resolve("person.json");
        serializer.dump(original, file.toString());

        Person restored = assertInstanceOf(Person.class, deserializer.load(file.toString()).getFirst());
        assertEquals("Alice", restored.name);
        assertEquals(30, restored.age);
        assertEquals(List.of("developer"), restored.tags);
    }

    @Test
    void roundTripsPersonWithAddress() throws Exception {
        Person original = new Person();
        original.name = "Bob";
        original.address = new Address();
        original.address.city = "Moscow";
        original.address.street = "Lenina";

        Path file = tempDir.resolve("person-address.json");
        serializer.dump(original, file.toString());

        Person restored = assertInstanceOf(Person.class, deserializer.load(file.toString()).getFirst());
        assertEquals("Moscow", restored.address.city);
        assertEquals("Lenina", restored.address.street);
    }

    @Test
    void roundTripsCyclicGraph() throws Exception {
        Node root = new Node();
        root.value = "root";
        Node child = new Node();
        child.value = "child";
        root.children.add(child);
        child.parent = root;

        Path file = tempDir.resolve("cyclic.json");
        serializer.dump(root, file.toString());

        Node restored = assertInstanceOf(Node.class, deserializer.load(file.toString()).getFirst());
        assertEquals("root", restored.value);
        assertEquals(1, restored.children.size());
        assertSame(restored, restored.children.get(0).parent);
    }

    @Test
    void roundTripsSharedReferences() throws Exception {
        Person alice = new Person();
        alice.name = "Alice";
        Person bob = new Person();
        bob.name = "Bob";
        bob.address = new Address();
        bob.address.city = "SPb";
        alice.address = bob.address;

        Path file = tempDir.resolve("shared.json");
        serializer.dumpList(List.of(alice, bob), file.toString());

        List<Object> restoredList = deserializer.load(file.toString());
        Person restoredAlice = assertInstanceOf(Person.class, restoredList.get(0));
        Person restoredBob = assertInstanceOf(Person.class, restoredList.get(1));
        assertSame(restoredAlice.address, restoredBob.address);
    }

    @Test
    void serializesArrayListAsRoot() throws Exception {
        List<String> original = new ArrayList<>(List.of("one", "two", "three"));

        Path file = tempDir.resolve("arraylist-root.json");
        serializer.dump(original, file.toString());

        List<?> restored = assertInstanceOf(ArrayList.class, deserializer.load(file.toString()).getFirst());
        assertEquals(original, restored);
    }

    @Test
    void serializesLinkedListAsRoot() throws Exception {
        LinkedList<Integer> original = new LinkedList<>(List.of(10, 20, 30));

        Path file = tempDir.resolve("linkedlist-root.json");
        serializer.dump(original, file.toString());

        Queue<?> restored = assertInstanceOf(LinkedList.class, deserializer.load(file.toString()).getFirst());
        assertEquals(3, restored.size());
    }

    @Test
    void serializesLinkedHashMapAsRoot() throws Exception {
        LinkedHashMap<String, Integer> original = new LinkedHashMap<>();
        original.put("a", 1);
        original.put("b", 2);

        Path file = tempDir.resolve("linkedhashmap-root.json");
        serializer.dump(original, file.toString());

        Map<?, ?> restored = assertInstanceOf(LinkedHashMap.class, deserializer.load(file.toString()).getFirst());
        assertEquals(original, restored);
    }

    @Test
    void roundTripsPrimitiveArray() throws Exception {
        int[] original = {1, 2, 3, 4};

        Path file = tempDir.resolve("int-array.json");
        serializer.dump(original, file.toString());

        int[] restored = assertInstanceOf(int[].class, deserializer.load(file.toString()).getFirst());
        assertEquals(4, restored.length);
        assertEquals(1, restored[0]);
    }

    @Test
    void roundTripsMixedTypeArray() throws Exception {
        MixedArrayHolder holder = new MixedArrayHolder();
        holder.mixed = new Object[]{new Person(), "string", 42, Color.RED};

        Path file = tempDir.resolve("mixed-array.json");
        serializer.dump(holder, file.toString());

        MixedArrayHolder restored = assertInstanceOf(MixedArrayHolder.class, deserializer.load(file.toString()).getFirst());
        assertEquals(4, restored.mixed.length);
        assertInstanceOf(Person.class, restored.mixed[0]);
        assertEquals("string", restored.mixed[1]);
    }

    @Test
    void filtersByDirectField() throws Exception {
        Person alice = new Person();
        alice.name = "Alice";
        alice.age = 25;
        Person bob = new Person();
        bob.name = "Bob";
        bob.age = 30;

        Path file = tempDir.resolve("filter-eq.json");
        serializer.dumpList(List.of(alice, bob), file.toString());

        List<Object> filtered = deserializer.load(file.toString(),
                JickleFilter.eq("name", "Alice"));

        assertEquals(1, filtered.size());
        assertEquals("Alice", assertInstanceOf(Person.class, filtered.getFirst()).name);
    }

    @Test
    void filtersWithAnd() throws Exception {
        Person p = new Person();
        p.name = "Alice";
        p.age = 25;

        Path file = tempDir.resolve("filter-and.json");
        serializer.dump(p, file.toString());

        List<Object> filtered = deserializer.load(file.toString(),
                JickleFilter.and(
                        JickleFilter.eq("name", "Alice"),
                        JickleFilter.gt("age", 20)
                ));

        assertEquals(1, filtered.size());
    }

    @Test
    void filtersWithOrAndNot() throws Exception {
        Person a = new Person(); a.name = "Alice"; a.age = 25;
        Person b = new Person(); b.name = "Bob"; b.age = 30;

        Path file = tempDir.resolve("filter-or-not.json");
        serializer.dumpList(List.of(a, b), file.toString());

        List<Object> filtered = deserializer.load(file.toString(),
                JickleFilter.or(
                        JickleFilter.eq("name", "Alice"),
                        JickleFilter.not(JickleFilter.eq("age", 30))
                ));

        assertEquals(1, filtered.size());
    }

    @Test
    void filtersNullChecks() throws Exception {
        Person p1 = new Person(); p1.name = "Alice";
        Person p2 = new Person(); p2.name = null;

        Path file = tempDir.resolve("filter-null.json");
        serializer.dumpList(List.of(p1, p2), file.toString());

        List<Object> filtered = deserializer.load(file.toString(), JickleFilter.isNull("name"));
        assertEquals(1, filtered.size());
    }

    @Test
    void roundTripsEnum() throws Exception {
        EnumHolder original = new EnumHolder();
        original.color = Color.GREEN;
        original.colors.add(Color.RED);
        original.colors.add(Color.BLUE);

        Path file = tempDir.resolve("enum.json");
        serializer.dump(original, file.toString());

        EnumHolder restored = assertInstanceOf(EnumHolder.class, deserializer.load(file.toString()).getFirst());
        assertEquals(Color.GREEN, restored.color);
        assertEquals(2, restored.colors.size());
    }

    @Test
    void roundTripsAllPrimitives() throws Exception {
        PrimitiveHolder original = new PrimitiveHolder();
        original.bool = true;
        original.b = 127;
        original.s = 32000;
        original.c = 'X';
        original.i = Integer.MAX_VALUE;
        original.l = Long.MAX_VALUE;
        original.f = 3.14f;
        original.d = Math.PI;
        original.str = "test";

        Path file = tempDir.resolve("primitives.json");
        serializer.dump(original, file.toString());

        PrimitiveHolder restored = assertInstanceOf(PrimitiveHolder.class, deserializer.load(file.toString()).getFirst());
        assertEquals(original.bool, restored.bool);
        assertEquals(original.d, restored.d);
    }

    @Test
    void preservesConcreteCollectionTypes() throws Exception {
        ContainerHolder original = new ContainerHolder();
        original.list = new LinkedList<>();
        original.list.add(42);

        Path file = tempDir.resolve("collection-type.json");
        serializer.dump(original, file.toString());

        ContainerHolder restored = assertInstanceOf(ContainerHolder.class, deserializer.load(file.toString()).getFirst());
        assertInstanceOf(LinkedList.class, restored.list);
    }

    @Test
    void serializesNullRoot() throws Exception {
        Path file = tempDir.resolve("null-root.json");
        serializer.dump(null, file.toString());

        List<Object> result = deserializer.load(file.toString());
        assertTrue(result.isEmpty());
    }

    @Test
    void serializesEmptyList() throws Exception {
        Path file = tempDir.resolve("empty-list.json");
        serializer.dumpList(List.of(), file.toString());

        List<Object> result = deserializer.load(file.toString());
        assertTrue(result.isEmpty());
    }

    @Test
    void keepsPrettyPrintedJsonStructure() throws Exception {
        Person p = new Person();
        p.name = "Test";

        Path file = tempDir.resolve("pretty.json");
        serializer.dump(p, file.toString());

        String json = Files.readString(file);
        assertTrue(json.contains(System.lineSeparator() + "  ["));
        assertTrue(json.contains("\"data\": {"));
    }

    @Test
    void rejectsNonAnnotatedClass() {
        class BadClass {
            public String value;
        }

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                serializer.dump(new BadClass(), tempDir.resolve("bad.json").toString()));

        assertTrue(ex.getMessage().contains("@JicklableClass"));
    }

    @Test
    void acceptsNonAnnotatedClassWithUnsafe() throws Exception {
        class UnsafeClass {
            public String value = "ok";
        }

        JickleSerializer unsafeSerializer = new JickleSerializer(true);
        JickleDeserializer unsafeDeserializer = new JickleDeserializer(true);

        Path file = tempDir.resolve("unsafe.json");
        UnsafeClass original = new UnsafeClass();
        unsafeSerializer.dump(original, file.toString());

        UnsafeClass restored = assertInstanceOf(UnsafeClass.class, unsafeDeserializer.load(file.toString()).getFirst());
        assertEquals("ok", restored.value);
    }

    @Test
    void doesNotFollowDotPathsInFilter() throws Exception {
        Person p = new Person();
        p.name = "Alice";

        Path file = tempDir.resolve("filter-dot.json");
        serializer.dump(p, file.toString());

        List<Object> filtered = deserializer.load(file.toString(), JickleFilter.eq("address.city", "Moscow"));
        assertTrue(filtered.isEmpty());
    }

    @Test
    void roundTripsQueueAndDequeFields() throws Exception {
        ContainerHolder original = new ContainerHolder();
        original.queue = new LinkedList<>();
        original.queue.offer("q1");
        original.deque = new ArrayDeque<>();
        original.deque.push(9.99);

        Path file = tempDir.resolve("queue-deque.json");
        serializer.dump(original, file.toString());

        ContainerHolder restored = assertInstanceOf(ContainerHolder.class, deserializer.load(file.toString()).getFirst());
        assertInstanceOf(LinkedList.class, restored.queue);
        assertInstanceOf(ArrayDeque.class, restored.deque);
    }

    @Test
    void preservesIdentityAfterMultipleRoundTrips() throws Exception {
        Person original = new Person();
        original.name = "IdentityTest";

        Path file = tempDir.resolve("identity.json");
        serializer.dump(original, file.toString());

        Person first = assertInstanceOf(Person.class, deserializer.load(file.toString()).getFirst());
        serializer.dump(first, file.toString());
        Person second = assertInstanceOf(Person.class, deserializer.load(file.toString()).getFirst());

        assertEquals(first.name, second.name);
    }

    @Test
    void roundTripsListOfLists() throws Exception {
        NestedHolder original = new NestedHolder();
        ArrayList<Integer> first = new ArrayList<>(List.of(1, 2, 3));
        ArrayList<Integer> second = new ArrayList<>(List.of(4, 5));
        original.listOfLists.add(first);
        original.listOfLists.add(second);

        Path file = tempDir.resolve("list-of-lists.json");
        serializer.dump(original, file.toString());

        NestedHolder restored = assertInstanceOf(NestedHolder.class, deserializer.load(file.toString()).getFirst());
        assertEquals(2, restored.listOfLists.size());
        assertEquals(List.of(1, 2, 3), restored.listOfLists.get(0));
    }

    @Test
    void roundTripsArrayOfArrays() throws Exception {
        NestedHolder original = new NestedHolder();
        original.arrayOfArrays = new String[][]{{"a", "b"}, {"c", "d", "e"}};

        Path file = tempDir.resolve("array-of-arrays.json");
        serializer.dump(original, file.toString());

        NestedHolder restored = assertInstanceOf(NestedHolder.class, deserializer.load(file.toString()).getFirst());
        assertEquals(2, restored.arrayOfArrays.length);
        assertEquals("a", restored.arrayOfArrays[0][0]);
        assertEquals("e", restored.arrayOfArrays[1][2]);
    }

    @Test
    void roundTripsDeepNestedCollections() throws Exception {
        NestedHolder original = new NestedHolder();
        original.listOfLists.add(new ArrayList<>(List.of(10, 20)));
        original.arrayOfArrays = new String[][]{{"x"}, {"y", "z"}};

        Path file = tempDir.resolve("deep-nested.json");
        serializer.dump(original, file.toString());

        NestedHolder restored = assertInstanceOf(NestedHolder.class, deserializer.load(file.toString()).getFirst());
        assertEquals(1, restored.listOfLists.size());
        assertEquals(2, restored.arrayOfArrays.length);
    }

    @Test
    void unsafeSupportsStaticNestedClass() throws Exception {
        JickleSerializer unsafeSer = new JickleSerializer(true);
        JickleDeserializer unsafeDes = new JickleDeserializer(true);

        UnsafeNested original = new UnsafeNested();
        original.value = "outer";
        original.inner = new UnsafeNested.Inner();
        original.inner.number = 999;

        Path file = tempDir.resolve("unsafe-nested.json");
        unsafeSer.dump(original, file.toString());

        UnsafeNested restored = assertInstanceOf(UnsafeNested.class, unsafeDes.load(file.toString()).getFirst());
        assertEquals("outer", restored.value);
        assertEquals(999, restored.inner.number);
    }

    @Test
    void unsafeSupportsHashMapRoot() throws Exception {
        JickleSerializer unsafeSer = new JickleSerializer(true);
        JickleDeserializer unsafeDes = new JickleDeserializer(true);

        HashMap<String, Integer> original = new HashMap<>();
        original.put("one", 1);
        original.put("two", 2);

        Path file = tempDir.resolve("hashmap-root.json");
        unsafeSer.dump(original, file.toString());

        Map<?, ?> restored = assertInstanceOf(HashMap.class, unsafeDes.load(file.toString()).getFirst());
        assertEquals(2, restored.size());
    }

    @Test
    void unsafeSupportsTreeMapAndPriorityQueue() throws Exception {
        JickleSerializer unsafeSer = new JickleSerializer(true);
        JickleDeserializer unsafeDes = new JickleDeserializer(true);

        TreeMap<String, String> map = new TreeMap<>();
        map.put("b", "2");
        map.put("a", "1");

        PriorityQueue<Integer> queue = new PriorityQueue<>();
        queue.offer(5);
        queue.offer(1);
        queue.offer(3);

        Path file = tempDir.resolve("treemap-pq.json");
        unsafeSer.dumpList(List.of(map, queue), file.toString());

        List<Object> restored = unsafeDes.load(file.toString());
        assertInstanceOf(TreeMap.class, restored.get(0));
        assertInstanceOf(PriorityQueue.class, restored.get(1));
    }
}