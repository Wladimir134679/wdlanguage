package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.embed.Callback;
import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.stdlib.Types;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

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

    public static NativeClass in(Environment scope, WindowTracker tracker) {
        return Types.in(scope, "Window", NativeClass.class, () -> build(tracker));
    }

    private static NativeClass build(WindowTracker tracker) {
        return NativeClass.named("Window")
                .field("title", StringValue.of("WDL Window"))
                .field("width", IntValue.of(400))
                .field("height", IntValue.of(300))

                .init((self, context, args, span) -> {
                    String title = self.get("title").display();
                    int width = (int) args.integer(1, "ширина", 400);
                    int height = (int) args.integer(2, "высота", 300);

                    JFrame frame = new JFrame(title);
                    frame.setSize(width, height);
                    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    frame.setLocationRelativeTo(null);

                    self.state(frame);
                    return NullValue.NULL;
                })

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

                .method("add", Signature.of(Signature.Param.required("component"),
                        Signature.Param.optional("constraint")), (self, context, args, span) -> {
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

                .method("setLayout", Signature.of(Signature.Param.required("layout")),
                        (self, context, args, span) -> {
                    LayoutManager lm = Layouts.extractLayout(args.at(0));
                    if (lm == null) {
                        throw args.bad(0, "компоновщик", "ожидался объект Layout");
                    }
                    frame(self).getContentPane().setLayout(lm);
                    frame(self).getContentPane().revalidate();
                    return NullValue.NULL;
                })

                .method("setTitle", Signature.of(Signature.Param.required("title")),
                        (self, context, args, span) -> {
                    String title = args.string(0, "заголовок");
                    frame(self).setTitle(title);
                    self.put("title", StringValue.of(title));
                    return NullValue.NULL;
                })

                .method("setSize", Signature.of(Signature.Param.required("width"),
                        Signature.Param.required("height")), (self, context, args, span) -> {
                    int w = (int) args.integer(0, "ширина");
                    int h = (int) args.integer(1, "высота");
                    frame(self).setSize(w, h);
                    self.put("width", IntValue.of(w));
                    self.put("height", IntValue.of(h));
                    return NullValue.NULL;
                })

                .method("center", Arity.exactly(0), (self, context, args, span) -> {
                    frame(self).setLocationRelativeTo(null);
                    return NullValue.NULL;
                })

                .method("onClose", Signature.of(Signature.Param.required("handler")),
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
