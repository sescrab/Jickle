package org.example;

import org.example.additional.CanvasDomain;
import org.example.additional.CanvasDomain.CanvasDocument;
import org.example.additional.CanvasDomain.SceneItem;
import org.example.additional.CanvasSvgRenderer;
import org.example.jickle.JickleDeserializer;
import org.example.jickle.JickleFilter;
import org.example.jickle.JickleSerializer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {
        CanvasDocument editor = CanvasDomain.createEditorCanvas();
        SceneItem[] scene = CanvasDomain.createSampleScene();

        JickleSerializer serializer = new JickleSerializer(false);
        JickleDeserializer deserializer = new JickleDeserializer(false);

        serializer.dump(editor, "canvas_editor.json");
        serializer.dumpList(Arrays.asList(scene), "scene_items.json");

        CanvasDocument restoredEditor = (CanvasDocument) deserializer.load("canvas_editor.json").getFirst();
        SceneItem[] restoredScene = toSceneItems(deserializer.load("scene_items.json"));
        SceneItem[] filteredScene = toSceneItems(deserializer.load(
                "scene_items.json",
                JickleFilter.not(
                        JickleFilter.and(
                                JickleFilter.eq("kind", "button"),
                                JickleFilter.eq("color", "red")
                        )
                )
        ));

        Files.writeString(Path.of("canvas_editor_original.svg"), CanvasSvgRenderer.render(editor), StandardCharsets.UTF_8);
        Files.writeString(Path.of("canvas_editor_restored.svg"), CanvasSvgRenderer.render(restoredEditor), StandardCharsets.UTF_8);
        Files.writeString(Path.of("scene_original.svg"), CanvasSvgRenderer.renderScene("", scene), StandardCharsets.UTF_8);
        Files.writeString(Path.of("scene_restored.svg"), CanvasSvgRenderer.renderScene("", restoredScene), StandardCharsets.UTF_8);
        Files.writeString(Path.of("scene_filtered.svg"), CanvasSvgRenderer.renderScene("", filteredScene), StandardCharsets.UTF_8);
    }

    private static SceneItem[] toSceneItems(List<Object> loadedItems) {
        return loadedItems.stream()
                .map(SceneItem.class::cast)
                .toArray(SceneItem[]::new);
    }
}
