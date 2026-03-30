package org.example.additional;

import org.example.additional.CanvasDomain.ButtonWidget;
import org.example.additional.CanvasDomain.CanvasDocument;
import org.example.additional.CanvasDomain.CheckBoxWidget;
import org.example.additional.CanvasDomain.CircleWidget;
import org.example.additional.CanvasDomain.DialWidget;
import org.example.additional.CanvasDomain.LabelWidget;
import org.example.additional.CanvasDomain.Layer;
import org.example.additional.CanvasDomain.RadioButtonWidget;
import org.example.additional.CanvasDomain.RadioGroupWidget;
import org.example.additional.CanvasDomain.RectangleWidget;
import org.example.additional.CanvasDomain.SharedStyle;
import org.example.additional.CanvasDomain.Widget;

import java.util.Locale;

public final class CanvasSvgRenderer {

    private static final int HORIZONTAL_PADDING = 28;
    private static final int TOP_PADDING = 56;
    private static final int BOTTOM_PADDING = 28;
    private static final int PANEL_X = 34;
    private static final int PANEL_Y = 76;
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 254;
    private static final int PANEL_LEFT_X = PANEL_X + 22;
    private static final int PANEL_RIGHT_X = PANEL_X + 250;
    private static final int ROW_STATUS_Y = PANEL_Y + 20;
    private static final int ROW_BUTTON_Y = PANEL_Y + 52;
    private static final int ROW_CHECKBOX_Y = PANEL_Y + 104;
    private static final int ROW_DIAL_Y = PANEL_Y + 54;
    private static final int ROW_GROUP_TITLE_Y = PANEL_Y + 114;
    private static final int ROW_RADIO1_Y = PANEL_Y + 148;
    private static final int ROW_RADIO2_Y = PANEL_Y + 178;
    private static final int ARTBOARD_X = 520;
    private static final int ARTBOARD_Y = 72;

    private CanvasSvgRenderer() {
    }

    public static String render(CanvasDocument document) {
        int svgWidth = document.width + HORIZONTAL_PADDING * 2;
        int svgHeight = document.height + TOP_PADDING + BOTTOM_PADDING;

        StringBuilder svg = new StringBuilder();
        svg.append(String.format(Locale.US,
                """
                <svg xmlns="http://www.w3.org/2000/svg" version="1.1" width="%d" height="%d" viewBox="0 0 %d %d">
                  <defs>
                    <linearGradient id="buttonGloss" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%%" stop-color="#FFFFFF"/>
                      <stop offset="100%%" stop-color="#FFFFFF" stop-opacity="0"/>
                    </linearGradient>
                  </defs>
                  <rect width="100%%" height="100%%" fill="#FAFAF7"/>
                  <text x="%d" y="18" font-family="Segoe UI, sans-serif" font-size="24" font-weight="700" dominant-baseline="hanging" fill="#263238">%s</text>
                  <rect x="%d" y="%d" width="%d" height="%d" rx="26" fill="#FFFDF8" stroke="#E0D8CC" stroke-width="1.5"/>
                  <text x="%d" y="%d" font-family="Segoe UI, sans-serif" font-size="12" font-weight="700" letter-spacing="1.6" fill="#8D6E63">CONTROL PANEL</text>
                  <line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#EFE5D8" stroke-width="1"/>
                """,
                svgWidth,
                svgHeight,
                svgWidth,
                svgHeight,
                HORIZONTAL_PADDING,
                escape(document.name),
                HORIZONTAL_PADDING + PANEL_X - 18,
                TOP_PADDING + PANEL_Y - 28,
                PANEL_WIDTH,
                PANEL_HEIGHT,
                HORIZONTAL_PADDING + PANEL_X + 22,
                TOP_PADDING + PANEL_Y - 2,
                HORIZONTAL_PADDING + PANEL_X + 22,
                TOP_PADDING + PANEL_Y + 14,
                HORIZONTAL_PADDING + PANEL_X + PANEL_WIDTH - 22,
                TOP_PADDING + PANEL_Y + 14
        ));

        for (Layer layer : document.layers) {
            svg.append("  <g data-layer=\"").append(escape(layer.id)).append("\" transform=\"translate(")
                    .append(HORIZONTAL_PADDING).append(' ').append(TOP_PADDING).append(")\">\n");
            for (Widget widget : layer.widgets) {
                appendWidget(svg, widget);
            }
            svg.append("  </g>\n");
        }

        svg.append("</svg>\n");
        return svg.toString();
    }

    private static void appendWidget(StringBuilder svg, Widget widget) {
        switch (widget) {
            case RectangleWidget rectangle -> appendRectangle(svg, rectangle);
            case CircleWidget circle -> appendCircle(svg, circle);
            case LabelWidget label -> appendLabel(svg, label);
            case ButtonWidget button -> appendButton(svg, button);
            case CheckBoxWidget checkBox -> appendCheckBox(svg, checkBox);
            case DialWidget dial -> appendDial(svg, dial);
            case RadioGroupWidget radioGroup -> appendRadioGroup(svg, radioGroup);
            case RadioButtonWidget radioButton -> appendRadioButton(svg, radioButton);
            case null, default -> {
            }
        }
    }

    private static void appendRectangle(StringBuilder svg, RectangleWidget widget) {
        SharedStyle style = widget.style;
        int x = widget.id != null && widget.id.contains("artboard") ? ARTBOARD_X : widget.x;
        int y = widget.id != null && widget.id.contains("artboard") ? ARTBOARD_Y : widget.y;
        svg.append("""
                <rect x="%d" y="%d" width="%d" height="%d" rx="%d" fill="%s" stroke="%s" stroke-width="%d"/>
                """.formatted(x, y, widget.width, widget.height, widget.cornerRadius, fill(style), stroke(style), strokeWidth(style)));
    }

    private static void appendCircle(StringBuilder svg, CircleWidget widget) {
        SharedStyle style = widget.style;
        int x = widget.x;
        int y = widget.y;
        if (widget.id != null && widget.id.contains("anchor")) {
            x = ARTBOARD_X + 42;
            y = ARTBOARD_Y + 54;
        }
        svg.append("""
                <circle cx="%d" cy="%d" r="%d" fill="%s" stroke="%s" stroke-width="%d"/>
                """.formatted(x + widget.radius, y + widget.radius, widget.radius, fill(style), stroke(style), strokeWidth(style)));
    }

    private static void appendLabel(StringBuilder svg, LabelWidget widget) {
        int textX = widget.x;
        int textY = widget.y;

        if (widget.owner instanceof ButtonWidget button) {
            textX = PANEL_LEFT_X;
            textY = ROW_STATUS_Y;
        } else if (widget.owner instanceof CheckBoxWidget checkBox) {
            textX = PANEL_LEFT_X + 44;
            textY = ROW_CHECKBOX_Y + 2;
        }

        svg.append("""
                <text x="%d" y="%d" font-family="Segoe UI, sans-serif" font-size="16" dominant-baseline="hanging" fill="%s">%s</text>
                """.formatted(textX, textY, stroke(widget.style), escape(widget.text)));
    }

    private static void appendButton(StringBuilder svg, ButtonWidget widget) {
        SharedStyle style = widget.style;
        int x = PANEL_LEFT_X;
        int y = ROW_BUTTON_Y;
        svg.append("""
                <rect x="%d" y="%d" width="168" height="44" rx="14" fill="%s" stroke="%s" stroke-width="%d"/>
                <rect x="%d" y="%d" width="168" height="44" rx="14" fill="url(#buttonGloss)" opacity="0.18"/>
                <text x="%d" y="%d" font-family="Segoe UI, sans-serif" font-size="16" font-weight="600" dominant-baseline="middle" fill="#263238">%s</text>
                """.formatted(x, y, fill(style), stroke(style), strokeWidth(style), x, y, x + 20, y + 24, escape(widget.caption)));
    }

    private static void appendCheckBox(StringBuilder svg, CheckBoxWidget widget) {
        SharedStyle style = widget.style;
        int boxX = PANEL_LEFT_X;
        int boxY = ROW_CHECKBOX_Y;
        svg.append("""
                <rect x="%d" y="%d" width="24" height="24" rx="5" fill="%s" stroke="%s" stroke-width="%d"/>
                """.formatted(boxX, boxY, fill(style), stroke(style), strokeWidth(style)));
        if (widget.checked) {
            svg.append("""
                    <path d="M %d %d L %d %d L %d %d" fill="none" stroke="#1B5E20" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
                    """.formatted(boxX + 5, boxY + 12, boxX + 11, boxY + 18, boxX + 19, boxY + 6));
        }
    }

    private static void appendDial(StringBuilder svg, DialWidget widget) {
        SharedStyle style = widget.style;
        int centerX = PANEL_RIGHT_X + 32;
        int centerY = ROW_DIAL_Y + 22;
        double angle = -135 + (270.0 * (widget.value - widget.min)) / Math.max(1, widget.max - widget.min);
        double radians = Math.toRadians(angle);
        double pointerX = centerX + 18 * Math.cos(radians);
        double pointerY = centerY + 18 * Math.sin(radians);

        svg.append("""
                <circle cx="%d" cy="%d" r="34" fill="#FFF8E1" stroke="#E6D4B8" stroke-width="1"/>
                <circle cx="%d" cy="%d" r="28" fill="%s" stroke="%s" stroke-width="%d"/>
                <line x1="%d" y1="%d" x2="%s" y2="%s" stroke="#263238" stroke-width="3" stroke-linecap="round"/>
                """.formatted(
                centerX,
                centerY,
                centerX,
                centerY,
                fill(style),
                stroke(style),
                strokeWidth(style),
                centerX,
                centerY,
                decimal(pointerX),
                decimal(pointerY)
        ));
    }

    private static void appendRadioGroup(StringBuilder svg, RadioGroupWidget widget) {
        int titleX = PANEL_RIGHT_X;
        int titleY = ROW_GROUP_TITLE_Y;
        svg.append("""
                <text x="%d" y="%d" font-family="Segoe UI, sans-serif" font-size="15" font-weight="600" dominant-baseline="hanging" fill="#37474F">%s</text>
                """.formatted(titleX, titleY, escape(widget.name)));
    }

    private static void appendRadioButton(StringBuilder svg, RadioButtonWidget widget) {
        SharedStyle style = widget.style;
        int centerX = PANEL_RIGHT_X + 12;
        int centerY = "radio-move".equals(widget.id) ? ROW_RADIO1_Y : ROW_RADIO2_Y;
        svg.append("""
                <circle cx="%d" cy="%d" r="10" fill="#FFFFFF" stroke="%s" stroke-width="%d"/>
                """.formatted(centerX, centerY, stroke(style), strokeWidth(style)));
        if (widget.checked) {
            svg.append("""
                    <circle cx="%d" cy="%d" r="5" fill="%s"/>
                    """.formatted(centerX, centerY, stroke(style)));
        }
        svg.append("""
                <text x="%d" y="%d" font-family="Segoe UI, sans-serif" font-size="14" dominant-baseline="middle" fill="#37474F">%s</text>
                """.formatted(centerX + 18, centerY, escape(widget.caption)));
    }

    private static String fill(SharedStyle style) {
        return style == null ? "#ECEFF1" : style.fillColor;
    }

    private static String stroke(SharedStyle style) {
        return style == null ? "#607D8B" : style.strokeColor;
    }

    private static int strokeWidth(SharedStyle style) {
        return style == null ? 1 : style.strokeWidth;
    }

    private static String decimal(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
