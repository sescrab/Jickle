package org.example.jickle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.additional.CanvasDomain;
import org.example.additional.CanvasDomain.ButtonWidget;
import org.example.additional.CanvasDomain.CanvasDocument;
import org.example.additional.CanvasDomain.CheckBoxWidget;
import org.example.additional.CanvasDomain.LabelWidget;
import org.example.additional.CanvasDomain.RadioButtonWidget;
import org.example.additional.CanvasDomain.RadioGroupWidget;
import org.example.additional.CanvasDomain.SceneItem;
import org.example.additional.CanvasDomain.SharedStyle;
import org.example.additional.CanvasDomain.Widget;
import org.example.additional.CanvasSvgRenderer;
import org.example.jickle.annotation.JicklableClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JickleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
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
    void roundTripsCanvasDocumentWithCyclesAndSharedReferences() throws Exception {
        CanvasDocument original = CanvasDomain.createEditorCanvas();

        Path file = tempDir.resolve("canvas.json");
        serializer.dump(original, file.toString());

        CanvasDocument restored = assertInstanceOf(CanvasDocument.class, deserializer.load(file.toString()).getFirst());

        assertEquals("Editor Canvas", restored.name);
        assertEquals(2, restored.layers.size());
        assertEquals(10, restored.elementsById.size());
        assertTrue(restored.layers instanceof LinkedList);
        assertTrue(restored.navigationOrder instanceof LinkedList);
        assertTrue(restored.selection instanceof LinkedList);

        ButtonWidget saveButton = assertInstanceOf(ButtonWidget.class, restored.elementsById.get("btn-save"));
        LabelWidget statusLabel = assertInstanceOf(LabelWidget.class, restored.elementsById.get("label-status"));
        CheckBoxWidget snapCheckbox = assertInstanceOf(CheckBoxWidget.class, restored.elementsById.get("check-snap"));
        LabelWidget snapLabel = assertInstanceOf(LabelWidget.class, restored.elementsById.get("label-snap"));
        RadioGroupWidget modeGroup = assertInstanceOf(RadioGroupWidget.class, restored.elementsById.get("group-mode"));
        RadioButtonWidget editMode = assertInstanceOf(RadioButtonWidget.class, restored.elementsById.get("radio-edit"));
        SharedStyle accent = restored.accentStyle;

        assertSame(restored.layers.getFirst().document, restored);
        assertSame(restored.layers.getFirst().mirrorLayer, restored.layers.get(1));
        assertSame(saveButton.statusLabel, statusLabel);
        assertSame(statusLabel.owner, saveButton);
        assertSame(snapCheckbox.label, snapLabel);
        assertSame(snapLabel.owner, snapCheckbox);
        assertSame(modeGroup.selected, editMode);
        assertSame(modeGroup.options.getLast(), editMode);
        assertSame(editMode.group, modeGroup);
        assertSame(restored.focusedWidget, saveButton);
        assertSame(restored.selection.getFirst(), saveButton);
        assertSame(restored.navigationOrder.peek(), saveButton);
        assertSame(saveButton.style, accent);
        assertSame(assertInstanceOf(CheckBoxWidget.class, restored.navigationOrder.stream().skip(1).findFirst().orElseThrow()).style, accent);
    }

    @Test
    void serializesLinkedListAsRegularObjectAndRoundTripsQueueRoot() throws Exception {
        CanvasDocument original = CanvasDomain.createEditorCanvas();

        Path file = tempDir.resolve("navigation.json");
        serializer.dump(original.navigationOrder, file.toString());

        JsonNode data = objectMapper.readTree(Files.readString(file)).get(0).get(0).get("data");
        assertFalse(data.has("is_container"));
        assertTrue(data.has("size"));
        assertTrue(data.has("modCount"));
        assertTrue(data.has("object_first"));
        assertTrue(data.has("object_last"));

        Queue<?> restored = assertInstanceOf(LinkedList.class, deserializer.load(file.toString()).getFirst());
        assertEquals(5, restored.size());
        assertEquals("btn-save", assertInstanceOf(ButtonWidget.class, restored.peek()).id);
    }

    @Test
    void serializesLinkedHashMapAsRegularObjectAndPreservesSharedInstances() throws Exception {
        CanvasDocument original = CanvasDomain.createEditorCanvas();

        Path file = tempDir.resolve("widget-map.json");
        serializer.dump(original.elementsById, file.toString());

        JsonNode data = objectMapper.readTree(Files.readString(file)).get(0).get(0).get("data");
        assertFalse(data.has("is_container"));
        assertFalse(data.has("entries"));
        assertTrue(data.has("size"));
        assertTrue(data.has("object_table") || data.has("object_head"));

        Map<?, ?> restored = assertInstanceOf(LinkedHashMap.class, deserializer.load(file.toString()).getFirst());
        assertEquals(10, restored.size());

        ButtonWidget saveButton = assertInstanceOf(ButtonWidget.class, restored.get("btn-save"));
        LabelWidget statusLabel = assertInstanceOf(LabelWidget.class, restored.get("label-status"));
        assertSame(saveButton.statusLabel, statusLabel);
    }

    @Test
    void serializesArrayListAsRegularObjectUsingTransientFields() throws Exception {
        ArrayList<String> original = new ArrayList<>(List.of("one", "two", "three"));

        Path file = tempDir.resolve("array-list.json");
        serializer.dump(original, file.toString());

        JsonNode data = objectMapper.readTree(Files.readString(file)).get(0).get(0).get("data");
        assertFalse(data.has("is_container"));
        assertTrue(data.has("size"));
        assertTrue(data.has("modCount"));
        assertTrue(data.has("object_elementData"));

        ArrayList<?> restored = assertInstanceOf(ArrayList.class, deserializer.load(file.toString()).getFirst());
        assertEquals(List.of("one", "two", "three"), restored);
    }

    @Test
    void filtersCanvasDocumentsByDirectFieldsOnly() throws Exception {
        CanvasDocument editor = CanvasDomain.createEditorCanvas();
        CanvasDocument dashboard = CanvasDomain.createDashboardCanvas();

        Path file = tempDir.resolve("canvas-list.json");
        serializer.dumpList(List.of(editor, dashboard), file.toString());

        List<Object> filtered = deserializer.load(
                file.toString(),
                JickleFilter.and(
                        JickleFilter.eq("name", "Editor Canvas"),
                        JickleFilter.eq("width", 1440)
                )
        );

        assertEquals(1, filtered.size());
        CanvasDocument restored = assertInstanceOf(CanvasDocument.class, filtered.getFirst());
        assertEquals("Editor Canvas", restored.name);
        assertEquals(1440, restored.width);
    }

    @Test
    void doesNotFollowReferencePathsDuringFiltering() throws Exception {
        CanvasDocument editor = CanvasDomain.createEditorCanvas();
        CanvasDocument dashboard = CanvasDomain.createDashboardCanvas();

        Path file = tempDir.resolve("canvas-filter.json");
        serializer.dumpList(List.of(editor, dashboard), file.toString());

        List<Object> filtered = deserializer.load(file.toString(), JickleFilter.eq("focusedWidget.id", "btn-save"));

        assertTrue(filtered.isEmpty());
    }

    @Test
    void roundTripsWidgetInterfaceArrayWithMixedConcreteTypes() throws Exception {
        CanvasDocument document = CanvasDomain.createEditorCanvas();
        Widget[] original = {
                document.elementsById.get("btn-save"),
                document.elementsById.get("label-status"),
                document.elementsById.get("group-mode")
        };

        Path file = tempDir.resolve("widget-array.json");
        serializer.dump(original, file.toString());

        Widget[] restored = assertInstanceOf(Widget[].class, deserializer.load(file.toString()).getFirst());
        assertEquals(3, restored.length);
        assertInstanceOf(ButtonWidget.class, restored[0]);
        assertInstanceOf(LabelWidget.class, restored[1]);
        assertInstanceOf(RadioGroupWidget.class, restored[2]);
        assertSame(((ButtonWidget) restored[0]).statusLabel, restored[1]);
    }

    @Test
    void keepsPrettyPrintedJsonAndArrayMetadataForInternalArrays() throws Exception {
        ArrayList<String> values = new ArrayList<>(List.of("one", "two"));

        Path file = tempDir.resolve("pretty.json");
        serializer.dump(values, file.toString());

        String json = Files.readString(file);
        assertTrue(json.contains(System.lineSeparator() + "  ["));
        assertTrue(json.contains("\"object_elementData\": "));
        assertTrue(json.contains("\"is_container\": true"));
        assertTrue(json.contains(System.lineSeparator() + "        \"elements\": ["));
    }

    @Test
    void rendersOriginalAndRestoredCanvasToComparableSvg() throws Exception {
        CanvasDocument original = CanvasDomain.createEditorCanvas();

        Path file = tempDir.resolve("canvas-svg.json");
        serializer.dump(original, file.toString());
        CanvasDocument restored = assertInstanceOf(CanvasDocument.class, deserializer.load(file.toString()).getFirst());

        String originalSvg = CanvasSvgRenderer.render(original);
        String restoredSvg = CanvasSvgRenderer.render(restored);

        assertTrue(originalSvg.contains("<svg"));
        assertTrue(restoredSvg.contains("<svg"));
        assertTrue(originalSvg.contains("Save layout"));
        assertTrue(restoredSvg.contains("Save layout"));
        assertTrue(originalSvg.contains("Editor mode"));
        assertTrue(restoredSvg.contains("Editor mode"));
        assertTrue(originalSvg.contains("circle"));
        assertTrue(restoredSvg.contains("circle"));
    }

    @Test
    void rendersSceneArrayAndVisuallyFiltersOutSelectedButtons() throws Exception {
        SceneItem[] original = CanvasDomain.createSampleScene();

        Path file = tempDir.resolve("scene-items.json");
        serializer.dumpList(Arrays.asList(original), file.toString());

        SceneItem[] restored = toSceneArray(deserializer.load(file.toString()));
        SceneItem[] filtered = toSceneArray(deserializer.load(
                file.toString(),
                JickleFilter.not(
                        JickleFilter.and(
                                JickleFilter.eq("kind", "button"),
                                JickleFilter.eq("color", "red")
                        )
                )
        ));

        String originalSvg = CanvasSvgRenderer.renderScene("", original);
        String restoredSvg = CanvasSvgRenderer.renderScene("", restored);
        String filteredSvg = CanvasSvgRenderer.renderScene("", filtered);

        assertEquals(original.length, restored.length);
        assertEquals(originalSvg, restoredSvg);
        assertEquals(original.length - 2, filtered.length);
        assertTrue(originalSvg.contains("Delete layer"));
        assertTrue(originalSvg.contains("Reset view"));
        assertFalse(filteredSvg.contains("Delete layer"));
        assertFalse(filteredSvg.contains("Reset view"));
        assertTrue(filteredSvg.contains("Save layout"));
        assertTrue(filteredSvg.contains("Publish"));
    }

    @Test
    void rejectsNonAnnotatedUserClassWhenUnsafeDisabled() {
        class BadCanvas {
            public Queue<String> names = new LinkedList<>(List.of("one", "two"));
        }

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                serializer.dump(new BadCanvas(), tempDir.resolve("bad.json").toString())
        );

        assertTrue(exception.getMessage().contains("@JicklableClass"));
    }

    @Test
    void preservesCustomQueueImplementationInInterfaceField() throws Exception {
        CanvasDocument original = CanvasDomain.createEditorCanvas();
        QueueHolder holder = new QueueHolder();
        holder.name = "navigation";
        holder.widgets = new LinkedList<>(original.navigationOrder);

        Path file = tempDir.resolve("queue-holder.json");
        serializer.dump(holder, file.toString());

        QueueHolder restored = assertInstanceOf(QueueHolder.class, deserializer.load(file.toString()).getFirst());
        assertNotNull(restored.widgets);
        assertTrue(restored.widgets instanceof LinkedList);
        assertEquals(5, restored.widgets.size());
        assertEquals("btn-save", assertInstanceOf(ButtonWidget.class, restored.widgets.peek()).id);
    }

    @JicklableClass
    static class QueueHolder {
        public String name;
        public Queue<Widget> widgets;
    }

    private SceneItem[] toSceneArray(List<Object> loadedItems) {
        return loadedItems.stream()
                .map(SceneItem.class::cast)
                .toArray(SceneItem[]::new);
    }
}
