package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.embed.Callback;
import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.stdlib.Types;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import javax.swing.JComboBox;
import java.util.List;

/**
 * Нативный класс {@code ComboBox}: выпадающий список Swing (JComboBox).
 */
public final class NativeComboBox {

    private NativeComboBox() {
    }

    public static NativeClass in(Environment scope) {
        return Types.in(scope, "ComboBox", NativeComboBox::build);
    }

    private static NativeClass build() {
        return NativeClass.named("ComboBox")
                .field("items", ArrayValue.of(List.of()))
                .field("enabled", BoolValue.TRUE)

                .init((self, context, args, span) -> {
                    JComboBox<String> comboBox = new JComboBox<>();
                    if (args.size() > 0 && args.at(0) instanceof ArrayValue items) {
                        for (int i = 0; i < items.size(); i++) {
                            comboBox.addItem(items.get(i).display());
                        }
                    }
                    boolean enabled = self.get("enabled").isTruthy();
                    comboBox.setEnabled(enabled);

                    self.state(comboBox);
                    return NullValue.NULL;
                })

                .method("addItem", Arity.exactly(1), (self, context, args, span) -> {
                    String itemStr = args.at(0).display();
                    comboBox(self).addItem(itemStr);
                    return NullValue.NULL;
                })

                .method("clear", Arity.exactly(0), (self, context, args, span) -> {
                    comboBox(self).removeAllItems();
                    return NullValue.NULL;
                })

                .method("getSelectedIndex", Arity.exactly(0), (self, context, args, span) ->
                        IntValue.of(comboBox(self).getSelectedIndex()))

                .method("setSelectedIndex", Arity.exactly(1), (self, context, args, span) -> {
                    int index = (int) args.integer(0, "индекс");
                    comboBox(self).setSelectedIndex(index);
                    return NullValue.NULL;
                })

                .method("getSelectedItem", Arity.exactly(0), (self, context, args, span) -> {
                    Object selected = comboBox(self).getSelectedItem();
                    return selected != null ? StringValue.of(selected.toString()) : NullValue.NULL;
                })

                .method("setEnabled", Arity.exactly(1), (self, context, args, span) -> {
                    boolean enabled = args.at(0).isTruthy();
                    comboBox(self).setEnabled(enabled);
                    self.put("enabled", BoolValue.of(enabled));
                    return NullValue.NULL;
                })

                .method("onChange", Arity.exactly(1), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    comboBox(self).addActionListener(GuiEvents.toActionListener(callback));
                    return NullValue.NULL;
                })

                .build();
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<String> comboBox(NativeInstance self) {
        return (JComboBox<String>) self.state();
    }
}
