package ru.wds.wdl.game.engine;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Окно игры: рамка Swing и панель, на которую попадает готовый кадр.
 * <p>
 * Панель <b>ничего не рисует сама</b> — она показывает картинку, которую цикл
 * кадров нарисовал в своём потоке. Это и есть та граница, из-за которой у движка
 * нет ни одной проблемы с потоками Swing: в EDT остаётся только {@code drawImage},
 * а вся игра — от обработчика скрипта до последнего пикселя — идёт в потоке,
 * который позвал {@code run()}.
 *
 * <h2>Ввод собирается здесь, а разбирается в цикле</h2>
 * Слушатели живут в EDT и складывают нажатия в {@link Input}. Функции скрипта
 * они не зовут: обработчик, выполненный в потоке интерфейса, подвешивает окно
 * ровно на своё время работы.
 */
public final class GameWindow {

    private final JFrame frame;
    private final Canvas canvas;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private GameWindow(JFrame frame, Canvas canvas) {
        this.frame = frame;
        this.canvas = canvas;
    }

    /**
     * Открывает окно и отдаёт его уже показанным.
     * <p>
     * Создание идёт в EDT, а вызывающий ждёт: дальше он сразу начнёт слать кадры,
     * и панель к этому моменту обязана существовать.
     *
     * @throws IllegalStateException в среде без графики — сказать об этом на месте
     *                               открытия честнее, чем показывать стек AWT
     */
    public static GameWindow open(String title, int width, int height, Input input) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("нет графической среды: окно открыть нельзя. "
                    + "Симуляцию без окна прогоняет step(dt)");
        }
        GameWindow[] created = new GameWindow[1];
        onSwingThread(() -> created[0] = build(title, width, height, input));
        return created[0];
    }

    private static GameWindow build(String title, int width, int height, Input input) {
        JFrame frame = new JFrame(title);
        Canvas canvas = new Canvas(width, height);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setResizable(false);
        frame.add(canvas);
        frame.pack();
        frame.setLocationRelativeTo(null);

        GameWindow window = new GameWindow(frame, canvas);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                window.open.set(false);
            }

            @Override
            public void windowClosed(WindowEvent event) {
                window.open.set(false);
            }
        });
        canvas.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                input.keyDown(event.getKeyCode());
            }

            @Override
            public void keyReleased(KeyEvent event) {
                input.keyUp(event.getKeyCode());
            }
        });
        canvas.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                // Клавиша, отпущенная в чужом окне, событием сюда не придёт —
                // и осталась бы нажатой навсегда.
                input.forgetAll();
            }
        });
        canvas.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                input.mouseMoved(event.getX(), event.getY());
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                input.mouseMoved(event.getX(), event.getY());
            }
        });
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                input.mouseButton(true);
                canvas.requestFocusInWindow();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                input.mouseButton(false);
            }
        });

        frame.setVisible(true);
        canvas.requestFocusInWindow();
        return window;
    }

    /** Открыто ли окно: цикл кадров спрашивает это каждый кадр. */
    public boolean isOpen() {
        return open.get();
    }

    public void title(String title) {
        SwingUtilities.invokeLater(() -> frame.setTitle(title));
    }

    public String title() {
        return frame.getTitle();
    }

    /** Показывает готовый кадр. Зовётся из потока игры, а не из EDT. */
    public void present(BufferedImage image) {
        canvas.show(image);
    }

    /** Закрывает окно; повторный вызов ничего не делает. */
    public void close() {
        open.set(false);
        SwingUtilities.invokeLater(frame::dispose);
    }

    private static void onSwingThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("открытие окна прервано", interrupted);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("окно не открылось", cause);
        }
    }

    /**
     * Панель, показывающая последний нарисованный кадр.
     * <p>
     * {@code final}, потому что конструктор трогает {@code setPreferredSize}
     * и {@code setFocusable}: у наследуемого класса это был бы вызов метода,
     * который наследник вправе переопределить, то есть утечка недостроенного
     * объекта ({@code -Xlint:this-escape}).
     */
    private static final class Canvas extends JPanel {

        /** Кадр, который показывают сейчас. Пишет поток игры, читает EDT. */
        private volatile BufferedImage frame;

        Canvas(int width, int height) {
            setPreferredSize(new Dimension(width, height));
            setFocusable(true);
            // Фон рисуем сами: кадр закрывает панель целиком, и чистить её нечем.
            setOpaque(true);
            setDoubleBuffered(true);
        }

        void show(BufferedImage image) {
            this.frame = image;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            BufferedImage image = frame;
            if (image != null) {
                graphics.drawImage(image, 0, 0, null);
            }
        }
    }
}
