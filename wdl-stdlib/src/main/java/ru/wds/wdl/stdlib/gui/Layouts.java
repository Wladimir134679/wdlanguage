package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.value.Value;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.LayoutManager;

/**
 * Создание менеджеров компоновки (LayoutManager) для Swing GUI.
 */
public final class Layouts {

    private Layouts() {
    }

    static NativeClass layoutClass() {
        return NativeClass.named("Layout")
                .build();
    }

    public static Value createFlow(NativeClass layoutClass, String alignStr, int hgap, int vgap) {
        int align = switch (alignStr.toLowerCase()) {
            case "left" -> FlowLayout.LEFT;
            case "right" -> FlowLayout.RIGHT;
            default -> FlowLayout.CENTER;
        };
        return wrap(layoutClass, new FlowLayout(align, hgap, vgap));
    }

    public static Value createBorder(NativeClass layoutClass, int hgap, int vgap) {
        return wrap(layoutClass, new BorderLayout(hgap, vgap));
    }

    public static Value createGrid(NativeClass layoutClass, int rows, int cols, int hgap, int vgap) {
        return wrap(layoutClass, new GridLayout(rows, cols, hgap, vgap));
    }

    public static LayoutManager extractLayout(Value value) {
        if (value instanceof ru.wds.wdl.value.types.InstanceObjectValue instance && instance.identity() instanceof NativeInstance self) {
            return self.state(LayoutManager.class);
        }
        return null;
    }

    private static Value wrap(NativeClass layoutClass, LayoutManager lm) {
        NativeInstance instance = new NativeInstance(layoutClass);
        instance.state(lm);
        return instance;
    }
}
