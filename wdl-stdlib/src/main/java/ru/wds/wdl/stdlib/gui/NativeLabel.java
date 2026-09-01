package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.Params;
import ru.wds.wdl.bridge.reflect.FromJava;
import ru.wds.wdl.value.types.NullValue;

import javax.swing.JLabel;

/**
 * Нативный класс {@code Label}: текстовая метка Swing (JLabel).
 * <p>
 * Своего — только создание: всё остальное метка уже умеет, и берётся это у неё
 * же ({@link FromJava}). Про {@code text} и {@code enabled} свойствами — см.
 * {@link NativeButton}.
 */
public final class NativeLabel {

    private NativeLabel() {
    }

    static NativeClass build() {
        return NativeClass.named("Label")
                .backing(JLabel.class)
                .init(Params.of()
                        .optional("text", "")
                        .optional("enabled", true),
                        (self, context, args, span) -> {
                            JLabel label = new JLabel(args.string(0, "текст"));
                            label.setEnabled(args.at(1).isTruthy());
                            self.state(label);
                            return NullValue.NULL;
                        })

                .members(FromJava.of(JLabel.class)
                        .bean("text")
                        .bean("enabled"))

                .build();
    }
}
