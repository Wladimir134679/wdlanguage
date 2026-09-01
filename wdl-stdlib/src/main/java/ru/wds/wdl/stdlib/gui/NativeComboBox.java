package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.reflect.FromJava;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.NullValue;

import javax.swing.JComboBox;
import java.util.List;

/**
 * Нативный класс {@code ComboBox}: выпадающий список Swing (JComboBox).
 */
public final class NativeComboBox {

    private NativeComboBox() {
    }

    static NativeClass build() {
        return NativeClass.named("ComboBox")
                .backing(JComboBox.class)
                .field("items", ArrayValue.of(List.of()))
                .param("enabled", BoolValue.TRUE)

                .init((self, context, args, span) -> {
                    JComboBox<String> comboBox = new JComboBox<>();
                    if (args.at(0) instanceof ArrayValue items) {
                        for (int i = 0; i < items.size(); i++) {
                            comboBox.addItem(items.get(i).display());
                        }
                    }
                    comboBox.setEnabled(args.at(1).isTruthy());
                    self.state(comboBox);
                    return NullValue.NULL;
                })

                // Всё, что список умеет сам. Единственное переименование — clear:
                // 'removeAllItems' это имя из Swing, а в языке то же действие
                // у массива, строки и карты зовётся 'clear'.
                .members(FromJava.of(JComboBox.class)
                        .bean("enabled")
                        .method("addItem")
                        .methodAs("clear", "removeAllItems")
                        .method("getSelectedIndex")
                        .method("setSelectedIndex")
                        .method("getSelectedItem"))

                .method("onChange", Signature.of(Param.required("handler")), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    ((JComboBox<?>) self.state()).addActionListener(
                            GuiEvents.toActionListener(callback, context));
                    return NullValue.NULL;
                })

                .build();
    }
}
