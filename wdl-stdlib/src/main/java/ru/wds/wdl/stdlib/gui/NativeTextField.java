package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.embed.Callback;
import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.stdlib.Types;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import javax.swing.JTextField;

/**
 * Нативный класс {@code TextField}: текстовое поле Swing (JTextField).
 */
public final class NativeTextField {

    private NativeTextField() {
    }

    public static NativeClass in(Environment scope) {
        return Types.in(scope, "TextField", NativeTextField::build);
    }

    private static NativeClass build() {
        return NativeClass.named("TextField")
                .field("text", StringValue.of(""))
                .field("enabled", BoolValue.TRUE)

                .init((self, context, args, span) -> {
                    String text = self.get("text").display();
                    boolean enabled = self.get("enabled").isTruthy();

                    JTextField textField = new JTextField(text);
                    textField.setEnabled(enabled);

                    self.state(textField);
                    return NullValue.NULL;
                })

                .method("setText", Arity.exactly(1), (self, context, args, span) -> {
                    String text = args.string(0, "текст");
                    textField(self).setText(text);
                    self.put("text", StringValue.of(text));
                    return NullValue.NULL;
                })

                .method("getText", Arity.exactly(0), (self, context, args, span) ->
                        StringValue.of(textField(self).getText()))

                .method("setEnabled", Arity.exactly(1), (self, context, args, span) -> {
                    boolean enabled = args.at(0).isTruthy();
                    textField(self).setEnabled(enabled);
                    self.put("enabled", BoolValue.of(enabled));
                    return NullValue.NULL;
                })

                .method("onChange", Arity.exactly(1), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    textField(self).getDocument().addDocumentListener(GuiEvents.toDocumentListener(callback));
                    return NullValue.NULL;
                })

                .method("onEnter", Arity.exactly(1), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    textField(self).addActionListener(GuiEvents.toActionListener(callback));
                    return NullValue.NULL;
                })

                .build();
    }

    private static JTextField textField(NativeInstance self) {
        return self.state(JTextField.class);
    }
}
