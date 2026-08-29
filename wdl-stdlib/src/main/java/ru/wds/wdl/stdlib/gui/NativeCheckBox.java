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

import javax.swing.JCheckBox;

/**
 * Нативный класс {@code CheckBox}: флажок Swing (JCheckBox).
 */
public final class NativeCheckBox {

    private NativeCheckBox() {
    }

    public static NativeClass in(Environment scope) {
        return Types.in(scope, "CheckBox", NativeClass.class, NativeCheckBox::build);
    }

    private static NativeClass build() {
        return NativeClass.named("CheckBox")
                .field("text", StringValue.of(""))
                .field("checked", BoolValue.FALSE)
                .field("enabled", BoolValue.TRUE)

                .init((self, context, args, span) -> {
                    String text = self.get("text").display();
                    boolean checked = self.get("checked").isTruthy();
                    boolean enabled = self.get("enabled").isTruthy();

                    JCheckBox checkBox = new JCheckBox(text, checked);
                    checkBox.setEnabled(enabled);

                    self.state(checkBox);
                    return NullValue.NULL;
                })

                .method("isChecked", Arity.exactly(0), (self, context, args, span) ->
                        BoolValue.of(checkBox(self).isSelected()))

                .method("setChecked", Signature.of(Signature.Param.required("checked")), (self, context, args, span) -> {
                    boolean checked = args.at(0).isTruthy();
                    checkBox(self).setSelected(checked);
                    self.put("checked", BoolValue.of(checked));
                    return NullValue.NULL;
                })

                .method("setEnabled", Signature.of(Signature.Param.required("enabled")), (self, context, args, span) -> {
                    boolean enabled = args.at(0).isTruthy();
                    checkBox(self).setEnabled(enabled);
                    self.put("enabled", BoolValue.of(enabled));
                    return NullValue.NULL;
                })

                .method("onChange", Signature.of(Signature.Param.required("handler")), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    checkBox(self).addItemListener(GuiEvents.toItemListener(callback, context));
                    return NullValue.NULL;
                })

                .build();
    }

    private static JCheckBox checkBox(NativeInstance self) {
        return self.state(JCheckBox.class);
    }
}
