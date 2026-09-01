package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.bridge.Params;
import ru.wds.wdl.bridge.reflect.FromJava;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.types.NullValue;

import javax.swing.JPanel;
import java.awt.Component;
import java.awt.LayoutManager;

/**
 * Нативный класс {@code Panel}: панель-контейнер Swing (JPanel).
 */
public final class NativePanel {

    private NativePanel() {
    }

    static NativeClass build() {
        return NativeClass.named("Panel")
                .backing(JPanel.class)
                .init(Params.of()
                        .optional("layout", NullValue.NULL)
                        .optional("enabled", true),
                        (self, context, args, span) -> {
                            JPanel panel = new JPanel();
                            if (args.size() > 0 && args.at(0) != NullValue.NULL) {
                                LayoutManager lm = Layouts.extractLayout(args.at(0));
                                if (lm != null) {
                                    panel.setLayout(lm);
                                }
                            }
                            panel.setEnabled(args.at(1).isTruthy());
                            self.state(panel);
                            return NullValue.NULL;
                        })

                .members(FromJava.of(JPanel.class)
                        .bean("enabled"))

                .method("add", Signature.of(Param.required("component"),
                        Param.optional("constraint")), (self, context, args, span) -> {
                    Component comp = ComponentUtils.extractComponent(args.at(0));
                    if (comp == null) {
                        throw args.bad(0, "компонент", "ожидался UI-компонент");
                    }
                    if (args.size() > 1) {
                        String constraint = args.string(1, "ограничение");
                        panel(self).add(comp, constraint);
                    } else {
                        panel(self).add(comp);
                    }
                    panel(self).revalidate();
                    panel(self).repaint();
                    return NullValue.NULL;
                })

                .method("setLayout", Signature.of(Param.required("layout")),
                        (self, context, args, span) -> {
                    LayoutManager lm = Layouts.extractLayout(args.at(0));
                    if (lm == null) {
                        throw args.bad(0, "компоновщик", "ожидался объект Layout");
                    }
                    panel(self).setLayout(lm);
                    panel(self).revalidate();
                    return NullValue.NULL;
                })

                .build();
    }

    private static JPanel panel(NativeInstance self) {
        return self.state(JPanel.class);
    }
}
