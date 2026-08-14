package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.embed.Callback;
import ru.wds.wdl.runtime.FatalError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;

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

    /**
     * Выполнить в потоке интерфейса и не ждать: {@code gui.later(fn)}.
     * <p>
     * <b>Единственный правильный способ тронуть окно из потока скрипта.</b> Swing
     * не потокобезопасен: {@code label.setText(...)} из {@code th.spawn} — это гонка,
     * которая проявляется не ошибкой, а неперерисованным или испорченным интерфейсом.
     * Раньше это было незаметно только потому, что замок запуска не давал потокам
     * работать одновременно; теперь мост обязателен.
     * <p>
     * Ошибка обработчика идёт в вывод запуска — по той же причине, что и в
     * {@link GuiEvents}: {@code System.err} обходит решение приложения о том,
     * куда печатает скрипт.
     */
    public static Value later(Callback callback, CallContext context) {
        SwingUtilities.invokeLater(() -> GuiEvents.safely(callback, context, "gui.later"));
        return NullValue.NULL;
    }

    /**
     * Выполнить в потоке интерфейса и дождаться: {@code gui.sync(fn)}.
     * <p>
     * Нужен там, где у интерфейса надо что-то <b>спросить</b>: текст поля, состояние
     * галочки. Отдельно от {@link #later}, потому что цена разная — {@code sync}
     * останавливает вызывающий поток до тех пор, пока очередь событий до него дойдёт,
     * а очередь может быть занята другим обработчиком.
     * <p>
     * Из самого EDT зовётся напрямую: {@code invokeAndWait} на потоке событий —
     * мгновенная взаимная блокировка, и Java отвечает на неё исключением. Скрипту же
     * это просто «выполнить сейчас», и вести себя по-разному в зависимости от того,
     * откуда позвали, он не должен.
     */
    public static Value sync(Callback callback, CallContext context, Span span) {
        if (SwingUtilities.isEventDispatchThread()) {
            return callback.call();
        }
        try {
            SwingUtilities.invokeAndWait(() -> GuiEvents.safely(callback, context, "gui.sync"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw FatalError.interrupted(span);
        } catch (InvocationTargetException impossible) {
            // safely() ловит всё, поэтому сюда попасть можно только из-за ошибки
            // в самом мосте.
            throw new IllegalStateException("обработчик gui.sync не поймал свою ошибку",
                    impossible.getCause());
        }
        return NullValue.NULL;
    }
}
