package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.stdlib.Types;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;
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

    public static NativeClass in(Environment scope) {
        return Types.in(scope, "Panel", NativePanel::build);
    }

    private static NativeClass build() {
        return NativeClass.named("Panel")
                .field("layout", NullValue.NULL)
                .field("enabled", BoolValue.TRUE)

                .init((self, context, args, span) -> {
                    JPanel panel = new JPanel();
                    if (args.size() > 0 && args.at(0) != NullValue.NULL) {
                        LayoutManager lm = Layouts.extractLayout(args.at(0));
                        if (lm != null) {
                            panel.setLayout(lm);
                        }
                    }
                    boolean enabled = self.get("enabled").isTruthy();
                    panel.setEnabled(enabled);

                    self.state(panel);
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
                        panel(self).add(comp, constraint);
                    } else {
                        panel(self).add(comp);
                    }
                    panel(self).revalidate();
                    panel(self).repaint();
                    return NullValue.NULL;
                })

                .method("setLayout", Signature.of(Signature.Param.required("layout")),
                        (self, context, args, span) -> {
                    LayoutManager lm = Layouts.extractLayout(args.at(0));
                    if (lm == null) {
                        throw args.bad(0, "компоновщик", "ожидался объект Layout");
                    }
                    panel(self).setLayout(lm);
                    panel(self).revalidate();
                    return NullValue.NULL;
                })

                .method("setEnabled", Signature.of(Signature.Param.required("enabled")),
                        (self, context, args, span) -> {
                    boolean enabled = args.at(0).isTruthy();
                    panel(self).setEnabled(enabled);
                    self.put("enabled", BoolValue.of(enabled));
                    return NullValue.NULL;
                })

                .build();
    }

    private static JPanel panel(NativeInstance self) {
        return self.state(JPanel.class);
    }
}
