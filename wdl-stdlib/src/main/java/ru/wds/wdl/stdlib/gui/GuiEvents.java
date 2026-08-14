package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.embed.Callback;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.event.ActionListener;
import java.awt.event.ItemListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;

/**
 * Мост для превращения колбэков WDL в слушатели событий Swing.
 */
public final class GuiEvents {

    private GuiEvents() {
    }

    public static ActionListener toActionListener(Callback callback) {
        return event -> {
            try {
                callback.call();
            } catch (Throwable t) {
                System.err.println("[WDL GUI Event Error] " + t.getMessage());
                t.printStackTrace();
            }
        };
    }

    public static DocumentListener toDocumentListener(Callback callback) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                callSafe();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                callSafe();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                callSafe();
            }

            private void callSafe() {
                try {
                    callback.call();
                } catch (Throwable t) {
                    System.err.println("[WDL GUI Document Listener Error] " + t.getMessage());
                    t.printStackTrace();
                }
            }
        };
    }

    public static ItemListener toItemListener(Callback callback) {
        return event -> {
            try {
                callback.call();
            } catch (Throwable t) {
                System.err.println("[WDL GUI Item Listener Error] " + t.getMessage());
                t.printStackTrace();
            }
        };
    }

    public static WindowListener toWindowCloseListener(Callback callback) {
        return new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                try {
                    callback.call();
                } catch (Throwable t) {
                    System.err.println("[WDL GUI Window Close Error] " + t.getMessage());
                    t.printStackTrace();
                }
            }
        };
    }
}
