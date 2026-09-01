package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.Params;
import ru.wds.wdl.bridge.reflect.FromJava;
import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.types.NullValue;

import javax.swing.JButton;

/**
 * Нативный класс {@code Button}: кнопка Swing (JButton).
 * <p>
 * Своего здесь ровно два: создание кнопки и {@code onClick} — обработчик, которого
 * у {@code JButton} нет и быть не может, потому что зовёт он функцию скрипта.
 * Всё остальное уже написано в Swing и берётся оттуда ({@link FromJava}).
 * <p>
 * {@code text} и {@code enabled} — <b>свойства</b>, а не поля: значение живёт
 * в {@code JButton}, и полем оно устаревало бы к следующему обращению, а запись
 * в поле ({@code b.text = "..."}) не доходила бы до кнопки. Аргументами создания
 * они при этом остаются — {@code new Button("+1")} работает как раньше.
 */
public final class NativeButton {

    private NativeButton() {
    }

    static NativeClass build() {
        return NativeClass.named("Button")
                .backing(JButton.class)
                .init(Params.of()
                        .optional("text", "")
                        .optional("enabled", true),
                        (self, context, args, span) -> {
                            JButton button = new JButton(args.string(0, "текст"));
                            button.setEnabled(args.at(1).isTruthy());
                            self.state(button);
                            return NullValue.NULL;
                        })

                .members(FromJava.of(JButton.class)
                        .bean("text")
                        .bean("enabled")
                        .method("doClick"))

                .method("onClick", Signature.of(Param.required("handler")), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    self.state(JButton.class).addActionListener(GuiEvents.toActionListener(callback, context));
                    return NullValue.NULL;
                })

                .build();
    }
}
