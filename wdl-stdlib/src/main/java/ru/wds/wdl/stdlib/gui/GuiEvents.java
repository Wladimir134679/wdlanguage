package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.runtime.WdlError;
import ru.wds.wdl.value.CallContext;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.event.ActionListener;
import java.awt.event.ItemListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;

/**
 * Мост между обработчиками wdl и слушателями Swing.
 *
 * <h2>Ошибка обработчика идёт в вывод запуска, а не в {@code System.err}</h2>
 * Раньше здесь стоял {@code printStackTrace}, и это был регресс, а не мелочь.
 * Вывод скрипта — <b>зависимость</b>: приложение решает, куда он идёт, — в игровой
 * чат, в лог, в строку теста. Обработчик кнопки, печатающий стек в консоль процесса,
 * обходит это решение и в приложении без консоли просто исчезает вместе с ошибкой.
 * <p>
 * Java-стек при этом не печатается вовсе: он описывает путь по методам Swing
 * и интерпретатора, а автору скрипта нужна его собственная строка. Место у ошибки уже
 * есть — его несёт сама {@link WdlError}.
 *
 * <h2>Обработчик выполняется в потоке EDT</h2>
 * И этого больше не скрывает никакой замок. Пока обработчик считает, интерфейс
 * не перерисовывается — значит, долгую работу надо уносить в {@code th.spawn},
 * а возвращаться в интерфейс через {@code gui.later(fn)}.
 */
public final class GuiEvents {

    private GuiEvents() {
    }

    public static ActionListener toActionListener(Callback callback, CallContext context) {
        return event -> safely(callback, context, "обработчик события");
    }

    public static DocumentListener toDocumentListener(Callback callback, CallContext context) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                safely(callback, context, "обработчик изменения текста");
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                safely(callback, context, "обработчик изменения текста");
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                safely(callback, context, "обработчик изменения текста");
            }
        };
    }

    public static ItemListener toItemListener(Callback callback, CallContext context) {
        return event -> safely(callback, context, "обработчик выбора");
    }

    public static WindowListener toWindowCloseListener(Callback callback, CallContext context) {
        return new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                safely(callback, context, "обработчик закрытия окна");
            }
        };
    }

    /**
     * Зовёт обработчик и не даёт его ошибке уйти в EDT.
     * <p>
     * Ловится всё: исключение, вышедшее в поток обработки событий Swing, роняет
     * не скрипт, а очередь событий — и интерфейс перестаёт отвечать по причине,
     * которую уже никак не связать со строкой скрипта.
     */
    static void safely(Callback callback, CallContext context, String what) {
        try {
            callback.call();
        } catch (WdlError error) {
            context.write(what + ": " + describe(error) + System.lineSeparator());
        } catch (RuntimeException | LinkageError failure) {
            context.write(what + ": " + failure + System.lineSeparator());
        }
    }

    private static String describe(WdlError error) {
        String kind = error.kindName();
        return kind == null ? error.getMessage() : kind + ": " + error.getMessage();
    }
}
