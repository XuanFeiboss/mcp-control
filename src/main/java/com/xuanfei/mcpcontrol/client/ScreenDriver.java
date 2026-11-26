package com.xuanfei.mcpcontrol.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the current vanilla screen and forwards real mouse/key events to its widgets, so the AI
 * navigates menus the same way a player does (main menu, world creation, inventory, chests, ...).
 */
public final class ScreenDriver {

    private static final int MAX_TEXT = 220;

    private final Minecraft minecraft;

    public ScreenDriver(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    public Screen screen() {
        return minecraft.screen;
    }

    public String screenClass() {
        Screen screen = screen();
        return screen == null ? "" : screen.getClass().getSimpleName();
    }

    public JsonObject describe() {
        Screen screen = screen();
        JsonObject object = new JsonObject();
        if (screen == null) {
            object.addProperty("open", false);
            object.addProperty("hint", "No screen is open: the player is in the world (use client_state) or nothing is focused.");
            return object;
        }
        object.addProperty("open", true);
        object.addProperty("screen", screen.getClass().getSimpleName());
        object.addProperty("title", Ref.text(Ref.callOpt(screen, "getTitle")));
        List<Widget> widgets = widgets(screen);
        JsonArray array = new JsonArray();
        for (Widget widget : widgets) {
            array.add(widget.toJson());
        }
        object.add("widgets", array);
        object.addProperty("widget_count", widgets.size());
        object.addProperty("edit_field_count", countEditFields(widgets));
        return object;
    }

    /** One screen element (button, text field, list row, tab, ...). */
    public record Widget(int index, Object handle, String type, String text, String key, String value,
                         int x, int y, int width, int height, boolean active, boolean visible,
                         boolean editable) {

        public int centerX() {
            return x + Math.max(1, width) / 2;
        }

        public int centerY() {
            return y + Math.max(1, height) / 2;
        }

        public JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("index", index);
            object.addProperty("type", type);
            if (!text.isBlank()) {
                object.addProperty("text", text);
            }
            if (!key.isBlank()) {
                object.addProperty("key", key);
            }
            if (editable) {
                object.addProperty("value", value);
            }
            object.addProperty("x", x);
            object.addProperty("y", y);
            object.addProperty("width", width);
            object.addProperty("height", height);
            object.addProperty("active", active);
            object.addProperty("visible", visible);
            object.addProperty("editable", editable);
            return object;
        }
    }

    @SuppressWarnings("unchecked")
    public List<GuiEventListener> children(Screen screen) {
        List<GuiEventListener> result = new ArrayList<>();
        if (screen == null) {
            return result;
        }
        Object raw = Ref.callOpt(screen, "children");
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof GuiEventListener listener) {
                    result.add(listener);
                }
            }
            return result;
        }
        Object renderables = Ref.get(screen, "renderables");
        if (renderables instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof GuiEventListener listener) {
                    result.add(listener);
                }
            }
        }
        return result;
    }

    public List<Widget> widgets(Screen screen) {
        List<Widget> widgets = new ArrayList<>();
        int index = 0;
        for (Object child : children(screen)) {
            Widget widget = describe(child, index);
            if (widget != null) {
                widgets.add(widget);
                index++;
            }
        }
        return widgets;
    }

    public Widget describe(Object child, int index) {
        if (child == null) {
            return null;
        }
        String type = child.getClass().getSimpleName();
        Object message = Ref.callOpt(child, "getMessage");
        if (message == null) {
            message = Ref.callOpt(child, "getNarration");
        }
        String text = trim(Ref.text(message));
        String key = Ref.translationKey(message);
        boolean editable = Ref.has(child, "setValue", 1);
        String value = "";
        if (editable) {
            Object current = Ref.callOpt(child, "getValue");
            value = current == null ? "" : trim(String.valueOf(current));
        }
        int x = number(child, "getX", "x");
        int y = number(child, "getY", "y");
        int width = number(child, "getWidth", "width");
        int height = number(child, "getHeight", "height");
        Boolean active = Ref.getBoolean(child, "active");
        if (active == null) {
            Object called = Ref.callOpt(child, "isActive");
            active = called instanceof Boolean bool ? bool : Boolean.TRUE;
        }
        Boolean visible = Ref.getBoolean(child, "visible");
        if (visible == null) {
            Object called = Ref.callOpt(child, "isVisible");
            visible = called instanceof Boolean bool ? bool : Boolean.TRUE;
        }
        return new Widget(index, child, type, text, key, value, x, y, width, height,
                active, visible, editable);
    }

    /** Clicks the widget with a real mouse click at its centre. */
    public boolean click(Widget widget) {
        if (widget == null) {
            return false;
        }
        Object handled = Ref.callOpt(widget.handle(), "mouseClicked",
                (double) widget.centerX(), (double) widget.centerY(), 0);
        return Boolean.TRUE.equals(handled);
    }

    /** Clicks the first enabled widget whose text or translation key contains any of the keywords. */
    public Widget clickByText(Screen screen, String... keywords) {
        for (Widget widget : widgets(screen)) {
            if (!widget.active() || !widget.visible()) {
                continue;
            }
            if (matches(widget, keywords)) {
                click(widget);
                return widget;
            }
        }
        return null;
    }

    public Widget findByText(Screen screen, String... keywords) {
        for (Widget widget : widgets(screen)) {
            if (matches(widget, keywords)) {
                return widget;
            }
        }
        return null;
    }

    public Widget findEditField(Screen screen, int wantedIndex) {
        int seen = 0;
        for (Widget widget : widgets(screen)) {
            if (!widget.editable()) {
                continue;
            }
            if (seen == wantedIndex) {
                return widget;
            }
            seen++;
        }
        return null;
    }

    public List<Widget> editFields(Screen screen) {
        List<Widget> fields = new ArrayList<>();
        for (Widget widget : widgets(screen)) {
            if (widget.editable()) {
                fields.add(widget);
            }
        }
        return fields;
    }

    public boolean setText(Widget widget, String value) {
        if (widget == null || !Ref.has(widget.handle(), "setValue", 1)) {
            return false;
        }
        Ref.callOpt(widget.handle(), "setValue", value);
        Ref.callOpt(widget.handle(), "setFocused", true);
        return true;
    }

    public boolean clearText(Widget widget) {
        return setText(widget, "");
    }

    /** Sends a raw key press to the current screen (e.g. 256 = ESC, 257 = ENTER, 258 = TAB). */
    public boolean pressKey(int keyCode) {
        Screen screen = screen();
        if (screen == null) {
            return false;
        }
        Object result = Ref.callOpt(screen, "keyPressed", keyCode, 0, 0);
        return Boolean.TRUE.equals(result);
    }

    public boolean typeChar(char character) {
        Screen screen = screen();
        if (screen == null) {
            return false;
        }
        Object result = Ref.callOpt(screen, "charTyped", character, 0);
        return Boolean.TRUE.equals(result);
    }

    /** Forwards a raw mouse click in GUI-scaled coordinates. */
    public boolean clickRaw(double x, double y, int button) {
        Screen screen = screen();
        if (screen == null) {
            return false;
        }
        Object result = Ref.callOpt(screen, "mouseClicked", x, y, button);
        return Boolean.TRUE.equals(result);
    }

    public boolean close() {
        Screen screen = screen();
        if (screen == null) {
            return false;
        }
        Ref.callOpt(screen, "onClose");
        return true;
    }

    public Widget findByType(Screen screen, String typeContains) {
        for (Widget widget : widgets(screen)) {
            if (widget.type().toLowerCase(Locale.ROOT).contains(typeContains.toLowerCase(Locale.ROOT))) {
                return widget;
            }
        }
        return null;
    }

    private static boolean matches(Widget widget, String... keywords) {
        String haystack = (widget.text() + "|" + widget.key()).toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()
                    && haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static int number(Object target, String method, String field) {
        Object called = Ref.callOpt(target, method);
        if (called instanceof Number value) {
            return value.intValue();
        }
        Object direct = Ref.get(target, field);
        return direct instanceof Number value ? value.intValue() : 0;
    }

    private static int countEditFields(List<Widget> widgets) {
        int count = 0;
        for (Widget widget : widgets) {
            if (widget.editable()) {
                count++;
            }
        }
        return count;
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace('\n', ' ').trim();
        return cleaned.length() <= MAX_TEXT ? cleaned : cleaned.substring(0, MAX_TEXT) + "...";
    }
}
