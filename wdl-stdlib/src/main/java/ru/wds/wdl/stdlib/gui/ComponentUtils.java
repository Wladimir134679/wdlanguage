package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.InstanceObjectValue;

import javax.swing.JFrame;
import java.awt.Component;

/**
 * Утилиты для работы с нативными экземплярами Swing-компонентов.
 */
public final class ComponentUtils {

    private ComponentUtils() {
    }

    public static Component extractComponent(Value value) {
        if (value instanceof InstanceObjectValue instance && instance.identity() instanceof NativeInstance self) {
            Object state = self.state();
            if (state instanceof Component comp) {
                return comp;
            } else if (state instanceof JFrame frame) {
                return frame.getContentPane();
            }
        }
        return null;
    }
}
