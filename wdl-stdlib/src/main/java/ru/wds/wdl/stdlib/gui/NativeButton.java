package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.embed.Callback;
import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.stdlib.Types;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import javax.swing.JButton;

/**
 * Нативный класс {@code Button}: кнопка Swing (JButton).
 */
public final class NativeButton {

    private NativeButton() {
    }

    public static NativeClass in(Environment scope) {
        return Types.in(scope, "Button", NativeButton::build);
    }

    private static NativeClass build() {
        return NativeClass.named("Button")
                .field("text", StringValue.of(""))
                .field("enabled", BoolValue.TRUE)

                .init((self, context, args, span) -> {
                    String text = self.get("text").display();
                    boolean enabled = self.get("enabled").isTruthy();

                    JButton button = new JButton(text);
                    button.setEnabled(enabled);

                    self.state(button);
                    return NullValue.NULL;
                })

                .method("onClick", Signature.of(Signature.Param.required("handler")), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    button(self).addActionListener(GuiEvents.toActionListener(callback, context));
                    return NullValue.NULL;
                })

                .method("setText", Signature.of(Signature.Param.required("text")), (self, context, args, span) -> {
                    String text = args.string(0, "текст");
                    button(self).setText(text);
                    self.put("text", StringValue.of(text));
                    return NullValue.NULL;
                })

                .method("getText", Arity.exactly(0), (self, context, args, span) ->
                        StringValue.of(button(self).getText()))

                .method("setEnabled", Signature.of(Signature.Param.required("enabled")), (self, context, args, span) -> {
                    boolean enabled = args.at(0).isTruthy();
                    button(self).setEnabled(enabled);
                    self.put("enabled", BoolValue.of(enabled));
                    return NullValue.NULL;
                })

                .build();
    }

    private static JButton button(NativeInstance self) {
        return self.state(JButton.class);
    }
}
