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

    public void waitUntilClosed(CallContext context) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        Runnable waitAction = () -> {
            synchronized (lock) {
                while (!openWindows.isEmpty()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        };
        if (context != null) {
            context.allowOtherThreads(waitAction);
        } else {
            waitAction.run();
        }
    }

    public void waitUntilWindowClosed(JFrame frame, CallContext context) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        Runnable waitAction = () -> {
            synchronized (lock) {
                while (openWindows.contains(frame)) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        };
        if (context != null) {
            context.allowOtherThreads(waitAction);
        } else {
            waitAction.run();
        }
    }

    public boolean hasOpenWindows() {
        synchronized (lock) {
            return !openWindows.isEmpty();
        }
    }
}
