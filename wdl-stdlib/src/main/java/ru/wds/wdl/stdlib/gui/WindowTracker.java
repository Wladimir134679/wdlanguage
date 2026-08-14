package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.value.CallContext;

import javax.swing.JFrame;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Отслеживание открытых GUI-окон и блокировка завершения скрипта до их закрытия.
 */
public final class WindowTracker {

    private final Set<JFrame> openWindows = Collections.synchronizedSet(new HashSet<>());
    private final Object lock = new Object();

    public void track(JFrame frame) {
        synchronized (lock) {
            if (openWindows.add(frame)) {
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent e) {
                        untrack(frame);
                    }

                    @Override
                    public void windowClosing(WindowEvent e) {
                        untrack(frame);
                    }
                });
            }
        }
    }

    public void untrack(JFrame frame) {
        synchronized (lock) {
            if (openWindows.remove(frame) && openWindows.isEmpty()) {
                lock.notifyAll();
            }
        }
    }

    public void waitUntilClosed() {
        waitUntilClosed(null);
    }

    /**
     * Ждёт закрытия всех окон.
     * <p>
     * Просто ждёт. Раньше ожидание заворачивалось в {@code allowOtherThreads}: замок
     * сеанса снимался, иначе обработчики кнопок не смогли бы войти в скрипт и окно
     * не закрылось бы никогда. Замка нет — обработчики входят сами, а контекст здесь
     * больше ни на что не влияет и остаётся только ради совместимости подписи.
     */
    public void waitUntilClosed(CallContext context) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        synchronized (lock) {
            while (!openWindows.isEmpty()) {
                if (!awaitOnLock()) {
                    return;
                }
            }
        }
    }

    /** Ждёт закрытия одного окна — по тому же правилу, что и {@link #waitUntilClosed}. */
    public void waitUntilWindowClosed(JFrame frame, CallContext context) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        synchronized (lock) {
            while (openWindows.contains(frame)) {
                if (!awaitOnLock()) {
                    return;
                }
            }
        }
    }

    /**
     * Одно ожидание на замке.
     *
     * @return {@code false}, если поток прервали, — ждать дальше нельзя: остановка
     *         скрипта снаружи обязана снимать и того, кто висит на окне
     */
    private boolean awaitOnLock() {
        try {
            lock.wait();
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean hasOpenWindows() {
        synchronized (lock) {
            return !openWindows.isEmpty();
        }
    }
}
