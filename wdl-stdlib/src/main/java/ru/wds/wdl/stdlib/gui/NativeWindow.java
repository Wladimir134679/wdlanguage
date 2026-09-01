package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.bridge.Params;
import ru.wds.wdl.bridge.reflect.FromJava;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.types.NullValue;

import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.Component;
import java.awt.LayoutManager;

/**
 * Нативный класс {@code Window}: окно приложений Java Swing (JFrame).
 */
public final class NativeWindow {

    private NativeWindow() {
    }

    static NativeClass build(WindowTracker tracker) {
        return NativeClass.named("Window")
                .backing(JFrame.class)
                .init(Params.of()
                        .optional("title", "WDL Window")
                        .optional("width", 400)
                        .optional("height", 300),
                        (self, context, args, span) -> {
                            int width = (int) args.integer(1, "ширина");
                            int height = (int) args.integer(2, "высота");

                            JFrame frame = new JFrame(args.string(0, "заголовок"));
                            frame.setSize(width, height);
                            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                            frame.setLocationRelativeTo(null);

                            self.state(frame);
                            return NullValue.NULL;
                        })

                // Заголовок и размеры окна — у самого окна: пользователь тянет рамку
                // мышью, и снимок, сделанный при создании, врал бы уже через секунду.
                // Ширина и высота только для чтения: у JFrame нет пары к getWidth(),
                // менять размер положено через setSize(w, h).
                .members(FromJava.of(JFrame.class)
                        .bean("title")
                        .bean("width")
                        .bean("height")
                        .method("setSize"))

                .method("show", Arity.exactly(0), (self, context, args, span) -> {
                    JFrame frame = frame(self);
                    frame.setVisible(true);
                    tracker.track(frame);
                    return NullValue.NULL;
                })

                .method("showAndWait", Arity.exactly(0), (self, context, args, span) -> {
                    JFrame frame = frame(self);
                    frame.setVisible(true);
                    tracker.track(frame);
                    tracker.waitUntilWindowClosed(frame, context);
                    return NullValue.NULL;
                })

                .method("hide", Arity.exactly(0), (self, context, args, span) -> {
                    JFrame frame = frame(self);
                    frame.setVisible(false);
                    tracker.untrack(frame);
                    return NullValue.NULL;
                })

                .method("close", Arity.exactly(0), (self, context, args, span) -> {
                    JFrame frame = frame(self);
                    frame.dispose();
                    tracker.untrack(frame);
                    return NullValue.NULL;
                })

                .method("add", Signature.of(Param.required("component"),
                        Param.optional("constraint")), (self, context, args, span) -> {
                    Component comp = ComponentUtils.extractComponent(args.at(0));
                    if (comp == null) {
                        throw args.bad(0, "компонент", "ожидался UI-компонент");
                    }
                    if (args.size() > 1) {
                        String constraint = args.string(1, "ограничение");
                        frame(self).getContentPane().add(comp, constraint);
                    } else {
                        frame(self).getContentPane().add(comp);
                    }
                    frame(self).getContentPane().revalidate();
                    frame(self).getContentPane().repaint();
                    return NullValue.NULL;
                })

                .method("setLayout", Signature.of(Param.required("layout")),
                        (self, context, args, span) -> {
                    LayoutManager lm = Layouts.extractLayout(args.at(0));
                    if (lm == null) {
                        throw args.bad(0, "компоновщик", "ожидался объект Layout");
                    }
                    frame(self).getContentPane().setLayout(lm);
                    frame(self).getContentPane().revalidate();
                    return NullValue.NULL;
                })

                .method("center", Arity.exactly(0), (self, context, args, span) -> {
                    frame(self).setLocationRelativeTo(null);
                    return NullValue.NULL;
                })

                .method("onClose", Signature.of(Param.required("handler")),
                        (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    frame(self).addWindowListener(GuiEvents.toWindowCloseListener(callback, context));
                    return NullValue.NULL;
                })

                .build();
    }

    private static JFrame frame(NativeInstance self) {
        return self.state(JFrame.class);
    }
}
