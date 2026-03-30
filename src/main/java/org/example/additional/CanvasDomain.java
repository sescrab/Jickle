package org.example.additional;

import org.example.jickle.annotation.JicklableClass;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public final class CanvasDomain {

    private CanvasDomain() {
    }

    public interface Widget {
    }

    public interface Checkable extends Widget {
    }

    public static CanvasDocument createEditorCanvas() {
        SharedStyle accent = new SharedStyle("accent", "#FFE082", "#6D4C41", 2);
        SharedStyle muted = new SharedStyle("muted", "#CFD8DC", "#455A64", 1);

        CanvasDocument document = new CanvasDocument();
        document.name = "Editor Canvas";
        document.width = 1440;
        document.height = 900;

        Layer controls = new Layer();
        controls.id = "layer-controls";
        controls.title = "Controls";
        controls.locked = false;
        controls.document = document;

        Layer overlay = new Layer();
        overlay.id = "layer-overlay";
        overlay.title = "Overlay";
        overlay.locked = false;
        overlay.document = document;

        controls.mirrorLayer = overlay;
        overlay.mirrorLayer = controls;

        LabelWidget statusLabel = new LabelWidget("label-status", 40, 28, "Saving disabled", muted);
        ButtonWidget saveButton = new ButtonWidget("btn-save", 40, 72, "Save layout", accent);
        CheckBoxWidget snapCheckbox = new CheckBoxWidget("check-snap", 40, 120, true, accent);
        LabelWidget snapLabel = new LabelWidget("label-snap", 84, 120, "Enable snap", muted);
        DialWidget opacityDial = new DialWidget("dial-opacity", 340, 72, 0, 100, 75, accent);
        RadioGroupWidget modeGroup = new RadioGroupWidget("group-mode", 340, 140, "Editor mode", muted);
        RadioButtonWidget moveMode = new RadioButtonWidget("radio-move", 352, 172, "Move", false, muted);
        RadioButtonWidget editMode = new RadioButtonWidget("radio-edit", 352, 204, "Edit", true, accent);
        RectangleWidget artboard = new RectangleWidget("rect-artboard", 560, 80, 640, 480, 24, muted);
        CircleWidget anchor = new CircleWidget("circle-anchor", 620, 140, 18, accent);

        statusLabel.owner = saveButton;
        saveButton.statusLabel = statusLabel;
        saveButton.actionTarget = artboard;
        saveButton.linkedWidget = statusLabel;

        snapCheckbox.label = snapLabel;
        snapCheckbox.linkedWidget = saveButton;
        snapLabel.owner = snapCheckbox;

        opacityDial.controlledWidget = artboard;
        opacityDial.linkedWidget = anchor;

        moveMode.group = modeGroup;
        editMode.group = modeGroup;
        modeGroup.options = new LinkedList<>(List.of(moveMode, editMode));
        modeGroup.selected = editMode;
        modeGroup.boundDial = opacityDial;
        modeGroup.linkedWidget = saveButton;

        artboard.linkedWidget = anchor;
        anchor.linkedWidget = artboard;

        controls.widgets = new LinkedList<>(List.of(
                statusLabel,
                saveButton,
                snapCheckbox,
                snapLabel,
                opacityDial,
                modeGroup,
                moveMode,
                editMode
        ));
        overlay.widgets = new LinkedList<>(List.of(artboard, anchor));

        for (Widget widget : controls.widgets) {
            attachToLayer(widget, controls);
        }
        for (Widget widget : overlay.widgets) {
            attachToLayer(widget, overlay);
        }

        document.layers = new LinkedList<>(List.of(controls, overlay));
        document.focusedWidget = saveButton;
        document.selection = new LinkedList<>(List.of(saveButton, artboard, statusLabel));
        document.navigationOrder = new LinkedList<>(List.of(saveButton, snapCheckbox, opacityDial, modeGroup, editMode));
        document.accentStyle = accent;
        document.activeGroup = modeGroup;
        document.elementsById = orderedMap(
                saveButton,
                statusLabel,
                snapCheckbox,
                snapLabel,
                opacityDial,
                modeGroup,
                moveMode,
                editMode,
                artboard,
                anchor
        );

        return document;
    }

    public static CanvasDocument createDashboardCanvas() {
        SharedStyle blue = new SharedStyle("blue", "#BBDEFB", "#0D47A1", 2);
        SharedStyle soft = new SharedStyle("soft", "#F5F5F5", "#78909C", 1);

        CanvasDocument document = new CanvasDocument();
        document.name = "Dashboard Canvas";
        document.width = 1280;
        document.height = 720;

        Layer widgets = new Layer();
        widgets.id = "layer-widgets";
        widgets.title = "Widgets";
        widgets.locked = false;
        widgets.document = document;

        LabelWidget title = new LabelWidget("label-title", 32, 24, "Operations Dashboard", blue);
        RectangleWidget card = new RectangleWidget("rect-card", 32, 80, 420, 180, 18, soft);
        DialWidget dial = new DialWidget("dial-traffic", 520, 120, 0, 100, 42, blue);

        title.owner = card;
        card.linkedWidget = dial;
        dial.controlledWidget = card;

        widgets.widgets = new LinkedList<>(List.of(title, card, dial));
        for (Widget widget : widgets.widgets) {
            attachToLayer(widget, widgets);
        }

        document.layers = new LinkedList<>(List.of(widgets));
        document.focusedWidget = dial;
        document.selection = new LinkedList<>(List.of(card));
        document.navigationOrder = new LinkedList<>(List.of(title, card, dial));
        document.accentStyle = blue;
        document.activeGroup = null;
        document.elementsById = orderedMap(title, card, dial);

        return document;
    }

    @JicklableClass
    public static class SharedStyle {
        public String styleId;
        public String fillColor;
        public String strokeColor;
        public int strokeWidth;

        public SharedStyle() {
        }

        public SharedStyle(String styleId, String fillColor, String strokeColor, int strokeWidth) {
            this.styleId = styleId;
            this.fillColor = fillColor;
            this.strokeColor = strokeColor;
            this.strokeWidth = strokeWidth;
        }
    }

    @JicklableClass
    public static class CanvasDocument {
        public String name;
        public int width;
        public int height;
        public List<Layer> layers;
        public Map<String, Widget> elementsById;
        public Widget focusedWidget;
        public List<Widget> selection;
        public Queue<Widget> navigationOrder;
        public SharedStyle accentStyle;
        public RadioGroupWidget activeGroup;

        public CanvasDocument() {
        }
    }

    @JicklableClass
    public static class Layer {
        public String id;
        public String title;
        public boolean locked;
        public List<Widget> widgets;
        public CanvasDocument document;
        public Layer mirrorLayer;

        public Layer() {
        }
    }

    @JicklableClass
    public static class WidgetBase implements Widget {
        public String id;
        public int x;
        public int y;
        public boolean visible;
        public SharedStyle style;
        public Layer layer;
        public Widget linkedWidget;

        public WidgetBase() {
        }

        public WidgetBase(String id, int x, int y, boolean visible, SharedStyle style) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.visible = visible;
            this.style = style;
        }
    }

    @JicklableClass
    public static class RectangleWidget extends WidgetBase {
        public int width;
        public int height;
        public int cornerRadius;

        public RectangleWidget() {
        }

        public RectangleWidget(String id, int x, int y, int width, int height, int cornerRadius, SharedStyle style) {
            super(id, x, y, true, style);
            this.width = width;
            this.height = height;
            this.cornerRadius = cornerRadius;
        }
    }

    @JicklableClass
    public static class CircleWidget extends WidgetBase {
        public int radius;

        public CircleWidget() {
        }

        public CircleWidget(String id, int x, int y, int radius, SharedStyle style) {
            super(id, x, y, true, style);
            this.radius = radius;
        }
    }

    @JicklableClass
    public static class LabelWidget extends WidgetBase {
        public String text;
        public Widget owner;

        public LabelWidget() {
        }

        public LabelWidget(String id, int x, int y, String text, SharedStyle style) {
            super(id, x, y, true, style);
            this.text = text;
        }
    }

    @JicklableClass
    public static class ButtonWidget extends WidgetBase {
        public String caption;
        public LabelWidget statusLabel;
        public Widget actionTarget;

        public ButtonWidget() {
        }

        public ButtonWidget(String id, int x, int y, String caption, SharedStyle style) {
            super(id, x, y, true, style);
            this.caption = caption;
        }
    }

    @JicklableClass
    public static class CheckBoxWidget extends WidgetBase implements Checkable {
        public boolean checked;
        public LabelWidget label;

        public CheckBoxWidget() {
        }

        public CheckBoxWidget(String id, int x, int y, boolean checked, SharedStyle style) {
            super(id, x, y, true, style);
            this.checked = checked;
        }
    }

    @JicklableClass
    public static class DialWidget extends WidgetBase {
        public int min;
        public int max;
        public int value;
        public Widget controlledWidget;

        public DialWidget() {
        }

        public DialWidget(String id, int x, int y, int min, int max, int value, SharedStyle style) {
            super(id, x, y, true, style);
            this.min = min;
            this.max = max;
            this.value = value;
        }
    }

    @JicklableClass
    public static class RadioButtonWidget extends WidgetBase implements Checkable {
        public String caption;
        public boolean checked;
        public RadioGroupWidget group;

        public RadioButtonWidget() {
        }

        public RadioButtonWidget(String id, int x, int y, String caption, boolean checked, SharedStyle style) {
            super(id, x, y, true, style);
            this.caption = caption;
            this.checked = checked;
        }
    }

    @JicklableClass
    public static class RadioGroupWidget extends WidgetBase {
        public String name;
        public LinkedList<RadioButtonWidget> options;
        public RadioButtonWidget selected;
        public DialWidget boundDial;

        public RadioGroupWidget() {
        }

        public RadioGroupWidget(String id, int x, int y, String name, SharedStyle style) {
            super(id, x, y, true, style);
            this.name = name;
        }
    }

    private static Map<String, Widget> orderedMap(Widget... widgets) {
        Map<String, Widget> result = new LinkedHashMap<>();
        for (Widget widget : widgets) {
            result.put(widgetId(widget), widget);
        }
        return result;
    }

    private static void attachToLayer(Widget widget, Layer layer) {
        switch (widget) {
            case RectangleWidget rectangle -> rectangle.layer = layer;
            case CircleWidget circle -> circle.layer = layer;
            case LabelWidget label -> label.layer = layer;
            case ButtonWidget button -> button.layer = layer;
            case CheckBoxWidget checkBox -> checkBox.layer = layer;
            case DialWidget dial -> dial.layer = layer;
            case RadioGroupWidget radioGroup -> radioGroup.layer = layer;
            case RadioButtonWidget radioButton -> radioButton.layer = layer;
            case null, default -> throw new IllegalArgumentException("Unsupported widget type: " + widget);
        }
    }

    private static String widgetId(Widget widget) {
        return switch (widget) {
            case RectangleWidget rectangle -> rectangle.id;
            case CircleWidget circle -> circle.id;
            case LabelWidget label -> label.id;
            case ButtonWidget button -> button.id;
            case CheckBoxWidget checkBox -> checkBox.id;
            case DialWidget dial -> dial.id;
            case RadioGroupWidget radioGroup -> radioGroup.id;
            case RadioButtonWidget radioButton -> radioButton.id;
            case null, default -> throw new IllegalArgumentException("Unsupported widget type: " + widget);
        };
    }
}
