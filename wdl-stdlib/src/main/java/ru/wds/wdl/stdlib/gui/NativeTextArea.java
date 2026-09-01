package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.bridge.Params;
import ru.wds.wdl.bridge.reflect.FromJava;
import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.types.NullValue;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * Нативный класс {@code TextArea}: многострочное текстовое поле с прокруткой
 * (JTextArea внутри JScrollPane).
 * <p>
 * Единственный класс {@code gui}, у которого состояние и источник членов — разные
 * объекты: в {@code state()} лежит прокрутка, а {@code text} и {@code append} нужны
 * у поля внутри неё. Это и есть случай {@link FromJava#via}: раньше здесь стояли
 * четыре лямбды-переходника, каждая из которых ещё и синхронизировала поле руками.
 */
public final class NativeTextArea {

    private NativeTextArea() {
    }

    static NativeClass build() {
        return NativeClass.named("TextArea")
                .backing(JScrollPane.class)
                .init(Params.of()
                        .optional("text", "")
                        .optional("rows", 10)
                        .optional("cols", 30)
                        .optional("enabled", true),
                        (self, context, args, span) -> {
                            JTextArea textArea = new JTextArea(args.string(0, "текст"),
                                    (int) args.integer(1, "строки"),
                                    (int) args.integer(2, "колонки"));
                            textArea.setEnabled(args.at(3).isTruthy());
                            self.state(new JScrollPane(textArea));
                            return NullValue.NULL;
                        })

                .members(FromJava.of(JTextArea.class)
                        .via(state -> ((JScrollPane) state).getViewport().getView())
                        .bean("text")
                        .bean("enabled")
                        .method("append"))

                .method("onChange", Signature.of(Param.required("handler")), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    textArea(self).getDocument().addDocumentListener(GuiEvents.toDocumentListener(callback, context));
                    return NullValue.NULL;
                })

                .build();
    }

    private static JTextArea textArea(NativeInstance self) {
        return (JTextArea) self.state(JScrollPane.class).getViewport().getView();
    }
}
