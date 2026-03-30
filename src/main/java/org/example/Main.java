package org.example;

import org.example.additional.CanvasDomain;
import org.example.additional.CanvasDomain.ButtonWidget;
import org.example.additional.CanvasDomain.CanvasDocument;
import org.example.additional.CanvasDomain.LabelWidget;
import org.example.additional.CanvasDomain.RadioGroupWidget;
import org.example.additional.CanvasDomain.Widget;
import org.example.additional.CanvasSvgRenderer;
import org.example.jickle.JickleDeserializer;
import org.example.jickle.JickleFilter;
import org.example.jickle.JickleSerializer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class Main {

    public static void main(String[] args) {
        try {
            CanvasDocument editor = CanvasDomain.createEditorCanvas();
            CanvasDocument dashboard = CanvasDomain.createDashboardCanvas();

            JickleSerializer serializer = new JickleSerializer(false);
            JickleDeserializer deserializer = new JickleDeserializer(false);

            Files.writeString(Path.of("canvas_editor_original.svg"), CanvasSvgRenderer.render(editor), StandardCharsets.UTF_8);
            serializer.dump(editor, "canvas_editor.json");
            serializer.dump(editor.navigationOrder, "canvas_navigation.json");
            serializer.dumpList(List.of(editor, dashboard), "canvas_documents.json");

            System.out.println("Serialized editor canvas:");
            System.out.println(Files.readString(Path.of("canvas_editor.json"), StandardCharsets.UTF_8));

            CanvasDocument restoredEditor = (CanvasDocument) deserializer.load("canvas_editor.json").getFirst();
            @SuppressWarnings("unchecked")
            Queue<Widget> restoredNavigation = (Queue<Widget>) deserializer.load("canvas_navigation.json").getFirst();
            List<Object> filteredDocuments = deserializer.load(
                    "canvas_documents.json",
                    JickleFilter.and(
                            JickleFilter.eq("name", "Editor Canvas"),
                            JickleFilter.eq("width", 1440)
                    )
            );

            Files.writeString(Path.of("canvas_editor_restored.svg"), CanvasSvgRenderer.render(restoredEditor), StandardCharsets.UTF_8);

            printSummary("Restored editor canvas", restoredEditor);
            System.out.println("Navigation queue type: " + restoredNavigation.getClass().getName());
            System.out.println("Filtered documents count (direct field filters only): " + filteredDocuments.size());
            System.out.println("SVG comparison files: canvas_editor_original.svg / canvas_editor_restored.svg");

            CanvasDocument filteredEditor = (CanvasDocument) filteredDocuments.getFirst();
            ButtonWidget restoredSaveButton = (ButtonWidget) filteredEditor.elementsById.get("btn-save");
            LabelWidget restoredStatus = (LabelWidget) filteredEditor.elementsById.get("label-status");
            RadioGroupWidget restoredModes = filteredEditor.activeGroup;

            System.out.println("Shared label preserved: " + (restoredSaveButton.statusLabel == restoredStatus));
            System.out.println("Layer cycle preserved: " + (filteredEditor.layers.getFirst().document == filteredEditor));
            System.out.println("Radio group cycle preserved: " + (restoredModes.options.getFirst().group == restoredModes));
            System.out.println("Linked list preserved in navigation: " + (restoredNavigation instanceof LinkedList<?>));
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private static void printSummary(String title, CanvasDocument document) {
        System.out.println();
        System.out.println(title + ":");
        System.out.println("  name = " + document.name);
        System.out.println("  layers = " + document.layers.size());
        System.out.println("  indexed widgets = " + document.elementsById.size());
        System.out.println("  focused widget = " + ((ButtonWidget) document.focusedWidget).id);
        System.out.println("  selected radio = " + document.activeGroup.selected.caption);
        System.out.println("  first layer widget count = " + document.layers.getFirst().widgets.size());
    }
}
