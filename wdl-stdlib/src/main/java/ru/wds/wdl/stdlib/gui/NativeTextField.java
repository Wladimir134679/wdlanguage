package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.Params;
import ru.wds.wdl.bridge.reflect.FromJava;
import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.types.NullValue;

import javax.swing.JTextField;

/**
 * Нативный класс {@code TextField}: текстовое поле Swing (JTextField).
 * <p>
 * Своё — создание и два обработчика; чтение и запись текста Swing уже умеет
 * ({@link FromJava}). Для поля ввода свойство {@code .text} особенно уместно:
 * значение там меняет пользователь, и снимок, сделанный при создании, устарел бы
 * на первом же нажатии клавиши.
 */
public final class NativeTextField {

    private NativeTextField() {
    }

    static NativeClass build() {
        return NativeClass.named("TextField")
                .backing(JTextField.class)
                .init(Params.of()
                        .optional("text", "")
                        .optional("enabled", true),
                        (self, context, args, span) -> {
                            JTextField field = new JTextField(args.string(0, "текст"));
                            field.setEnabled(args.at(1).isTruthy());
                            self.state(field);
                            return NullValue.NULL;
                        })

                .members(FromJava.of(JTextField.class)
                        .bean("text")
                        .bean("enabled"))

                .method("onChange", Signature.of(Param.required("handler")), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    self.state(JTextField.class).getDocument()
                            .addDocumentListener(GuiEvents.toDocumentListener(callback, context));
                    return NullValue.NULL;
                })

                .method("onEnter", Signature.of(Param.required("handler")), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    self.state(JTextField.class)
                            .addActionListener(GuiEvents.toActionListener(callback, context));
                    return NullValue.NULL;
                })

                .build();
    }
}
