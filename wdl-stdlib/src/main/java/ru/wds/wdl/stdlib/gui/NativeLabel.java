package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.stdlib.Types;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import javax.swing.JLabel;

/**
 * Нативный класс {@code Label}: текстовая метка Swing (JLabel).
 */
public final class NativeLabel {

    private NativeLabel() {
    }

    public static NativeClass in(Environment scope) {
        return Types.in(scope, "Label", NativeClass.class, NativeLabel::build);
    }

    private static NativeClass build() {
        return NativeClass.named("Label")
                .field("text", StringValue.of(""))
                .field("enabled", BoolValue.TRUE)

                .init((self, context, args, span) -> {
                    String text = self.get("text").display();
                    boolean enabled = self.get("enabled").isTruthy();

                    JLabel label = new JLabel(text);
                    label.setEnabled(enabled);

                    self.state(label);
                    return NullValue.NULL;
                })

                .method("setText", Signature.of(Signature.Param.required("text")), (self, context, args, span) -> {
                    String text = args.string(0, "текст");
                    label(self).setText(text);
                    self.put("text", StringValue.of(text));
                    return NullValue.NULL;
                })

                .method("getText", Arity.exactly(0), (self, context, args, span) ->
                        StringValue.of(label(self).getText()))

                .method("setEnabled", Signature.of(Signature.Param.required("enabled")), (self, context, args, span) -> {
                    boolean enabled = args.at(0).isTruthy();
                    label(self).setEnabled(enabled);
                    self.put("enabled", BoolValue.of(enabled));
                    return NullValue.NULL;
                })

                .build();
    }

    private static JLabel label(NativeInstance self) {
        return self.state(JLabel.class);
    }
}
