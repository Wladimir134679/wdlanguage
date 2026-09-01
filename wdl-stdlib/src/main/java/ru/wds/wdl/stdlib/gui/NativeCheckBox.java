package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.Params;
import ru.wds.wdl.bridge.reflect.FromJava;
import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.types.NullValue;

import javax.swing.JCheckBox;

/**
 * Нативный класс {@code CheckBox}: флажок Swing (JCheckBox).
 * <p>
 * В Swing флажок «выбран» зовётся {@code selected}, в скрипте — {@code checked}:
 * имена расходятся, и источник членов умеет их переименовывать
 * ({@link FromJava#beanAs}), а не заставляет писать переходник лямбдой.
 */
public final class NativeCheckBox {

    private NativeCheckBox() {
    }

    static NativeClass build() {
        return NativeClass.named("CheckBox")
                .backing(JCheckBox.class)
                .init(Params.of()
                        .optional("text", "")
                        .optional("checked", false)
                        .optional("enabled", true),
                        (self, context, args, span) -> {
                            JCheckBox box = new JCheckBox(args.string(0, "текст"),
                                    args.at(1).isTruthy());
                            box.setEnabled(args.at(2).isTruthy());
                            self.state(box);
                            return NullValue.NULL;
                        })

                .members(FromJava.of(JCheckBox.class)
                        .bean("text")
                        .bean("enabled")
                        .beanAs("checked", "selected"))

                .method("onChange", Signature.of(Param.required("handler")), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    self.state(JCheckBox.class).addItemListener(GuiEvents.toItemListener(callback, context));
                    return NullValue.NULL;
                })

                .build();
    }
}
