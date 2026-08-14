package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.embed.Callback;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Диалоговые окна и служебные функции Swing GUI.
 */
public final class Dialogs {

    private Dialogs() {
    }

    public static Value alert(String message, String title) {
        JOptionPane.showMessageDialog(null, message, title, JOptionPane.INFORMATION_MESSAGE);
        return NullValue.NULL;
    }

    public static Value confirm(String message, String title) {
        int result = JOptionPane.showConfirmDialog(null, message, title, JOptionPane.YES_NO_OPTION);
        return BoolValue.of(result == JOptionPane.YES_OPTION);
    }

    public static Value prompt(String message, String defaultText) {
        String result = JOptionPane.showInputDialog(null, message, defaultText);
        return result != null ? StringValue.of(result) : NullValue.NULL;
    }

    public static Value runLater(Callback callback) {
        SwingUtilities.invokeLater(() -> {
            try {
                callback.call();
            } catch (Throwable t) {
                System.err.println("[WDL GUI runLater Error] " + t.getMessage());
                t.printStackTrace();
            }
        });
        return NullValue.NULL;
    }
}
