package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.embed.Callback;
import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.stdlib.Types;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * Нативный класс {@code TextArea}: многострочное текстовое поле с прокруткой (JTextArea + JScrollPane).
 */
public final class NativeTextArea {

    private NativeTextArea() {
    }

    public static NativeClass in(Environment scope) {
        return Types.in(scope, "TextArea", NativeTextArea::build);
    }

    private static NativeClass build() {
        return NativeClass.named("TextArea")
                .field("text", StringValue.of(""))
                .field("rows", IntValue.of(10))
                .field("cols", IntValue.of(30))
                .field("enabled", BoolValue.TRUE)

                .init((self, context, args, span) -> {
                    String text = self.get("text").display();
                    int rows = (int) args.integer(1, "строки", 10);
                    int cols = (int) args.integer(2, "колонки", 30);
                    boolean enabled = self.get("enabled").isTruthy();

                    JTextArea textArea = new JTextArea(text, rows, cols);
                    textArea.setEnabled(enabled);
                    JScrollPane scrollPane = new JScrollPane(textArea);

                    self.state(scrollPane);
                    return NullValue.NULL;
                })

                .method("setText", Arity.exactly(1), (self, context, args, span) -> {
                    String text = args.string(0, "текст");
                    textArea(self).setText(text);
                    self.put("text", StringValue.of(text));
                    return NullValue.NULL;
                })

                .method("getText", Arity.exactly(0), (self, context, args, span) ->
                        StringValue.of(textArea(self).getText()))

                .method("append", Arity.exactly(1), (self, context, args, span) -> {
                    String text = args.string(0, "текст");
                    textArea(self).append(text);
                    self.put("text", StringValue.of(textArea(self).getText()));
                    return NullValue.NULL;
                })

                .method("setEnabled", Arity.exactly(1), (self, context, args, span) -> {
                    boolean enabled = args.at(0).isTruthy();
                    textArea(self).setEnabled(enabled);
                    self.put("enabled", BoolValue.of(enabled));
                    return NullValue.NULL;
                })

                .method("onChange", Arity.exactly(1), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    textArea(self).getDocument().addDocumentListener(GuiEvents.toDocumentListener(callback, context));
                    return NullValue.NULL;
                })

                .build();
    }

    private static JTextArea textArea(NativeInstance self) {
        JScrollPane scrollPane = self.state(JScrollPane.class);
        return (JTextArea) scrollPane.getViewport().getView();
    }
}
