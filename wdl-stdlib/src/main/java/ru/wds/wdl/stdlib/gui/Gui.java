package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.bridge.Module;
import ru.wds.wdl.module.Library;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.NullValue;

import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * Встроенный модуль {@code sys.gui}: графический интерфейс пользователя на Java Swing.
 *
 * <pre>{@code
 * import sys.gui as gui
 *
 * win = new gui.Window("Счётчик", 300, 200)
 * win.setLayout(gui.flow())
 *
 * lbl = new gui.Label("Счётчик: 0")
 * btn = new gui.Button("+1")

 * count = 0
 * btn.onClick(def () {
 *     count += 1
 *     lbl.setText("Счётчик: " + count)
 * })

 * win.add(lbl)
 * win.add(btn)
 * win.show()
 * }</pre>
 */
public final class Gui {

    private final WindowTracker tracker = new WindowTracker();

    private Gui() {
    }

    public static Library library() {
        return new Gui().module();
    }

    /**
     * Модуль этого запуска: девять типов и фабрики к ним.
     * <p>
     * Фабрики компоновщиков и диалогов замкнуты на собранные классы, поэтому стоят
     * в {@link Module.Builder#install}: типы к этому шагу уже в области, и берутся
     * они оттуда — тем же {@link Module#typeIn}, которым их ставил построитель.
     */
    private Library module() {
        return Module.named("sys/gui")
                .doc("окна и виджеты Swing: окно, панель и элементы на ней")
                .type("Layout", scope -> Layouts.layoutClass())
                .doc("компоновщик: как расставить элементы в панели")
                .type("Window", scope -> NativeWindow.build(tracker))
                .doc("окно: заголовок, размер, show()")
                .type("Panel", scope -> NativePanel.build())
                .doc("панель: держит элементы и их компоновку")
                .type("Button", scope -> NativeButton.build())
                .doc("кнопка: текст и обработчик нажатия")
                .type("Label", scope -> NativeLabel.build())
                .doc("надпись")
                .type("TextField", scope -> NativeTextField.build())
                .doc("однострочное поле ввода")
                .type("TextArea", scope -> NativeTextArea.build())
                .doc("многострочное поле ввода с прокруткой")
                .type("CheckBox", scope -> NativeCheckBox.build())
                .doc("флажок")
                .type("ComboBox", scope -> NativeComboBox.build())
                .doc("выпадающий список")
                .install(this::functions)
                // Закрытие модуля держит завершение процесса, пока пользователь
                // не закроет окна: обработчики кнопок всё это время работают.
                .onClose(tracker::waitUntilClosed)
                .build();
    }

    private void functions(Environment scope) {
        NativeClass layout = Module.typeIn(scope, "Layout");
        NativeClass panel = Module.typeIn(scope, "Panel");

        // Фабрики компоновщиков. Имена параметров объявлены, поэтому
        // 'grid(rows: 2, cols: 3)' читается, а 'grid(2, 3)' продолжает работать.
        scope.define("flow", BuiltinFunction.of("flow", Signature.of(
                        Param.optional("align", "center"),
                        Param.optional("hgap", 5),
                        Param.optional("vgap", 5)),
                (context, arguments, span) -> {
                    String align = arguments.size() > 0 ? arguments.string(0, "выравнивание") : "center";
                    int hgap = (int) arguments.integer(1, "горизонтальный отступ", 5);
                    int vgap = (int) arguments.integer(2, "вертикальный отступ", 5);
                    return Layouts.createFlow(layout, align, hgap, vgap);
                }));

        scope.define("border", BuiltinFunction.of("border", Signature.of(
                        Param.optional("hgap", 0),
                        Param.optional("vgap", 0)),
                (context, arguments, span) -> {
                    int hgap = (int) arguments.integer(0, "горизонтальный отступ", 0);
                    int vgap = (int) arguments.integer(1, "вертикальный отступ", 0);
                    return Layouts.createBorder(layout, hgap, vgap);
                }));

        scope.define("grid", BuiltinFunction.of("grid", Signature.of(
                        Param.required("rows"),
                        Param.required("cols"),
                        Param.optional("hgap", 0),
                        Param.optional("vgap", 0)),
                (context, arguments, span) -> {
                    int rows = (int) arguments.integer(0, "строки");
                    int cols = (int) arguments.integer(1, "колонки");
                    int hgap = (int) arguments.integer(2, "горизонтальный отступ", 0);
                    int vgap = (int) arguments.integer(3, "вертикальный отступ", 0);
                    return Layouts.createGrid(layout, rows, cols, hgap, vgap);
                }));

        scope.define("vbox", BuiltinFunction.of("vbox", Arity.exactly(0),
                (context, arguments, span) -> createBoxPanel(panel, BoxLayout.Y_AXIS)));

        scope.define("hbox", BuiltinFunction.of("hbox", Arity.exactly(0),
                (context, arguments, span) -> createBoxPanel(panel, BoxLayout.X_AXIS)));

        // Модальные диалоги и утилиты
        scope.define("alert", BuiltinFunction.of("alert", Signature.of(
                        Param.required("text"),
                        Param.optional("title", "Информация")),
                (context, arguments, span) -> {
                    String msg = arguments.at(0).display();
                    String title = arguments.size() > 1 ? arguments.string(1, "заголовок") : "Информация";
                    return Dialogs.alert(msg, title);
                }));

        scope.define("confirm", BuiltinFunction.of("confirm", Signature.of(
                        Param.required("text"),
                        Param.optional("title", "Подтверждение")),
                (context, arguments, span) -> {
                    String msg = arguments.at(0).display();
                    String title = arguments.size() > 1 ? arguments.string(1, "заголовок") : "Подтверждение";
                    return Dialogs.confirm(msg, title);
                }));

        scope.define("prompt", BuiltinFunction.of("prompt", Signature.of(
                        Param.required("text"),
                        Param.optional("initial", "")),
                (context, arguments, span) -> {
                    String msg = arguments.at(0).display();
                    String defText = arguments.size() > 1 ? arguments.string(1, "текст по умолчанию") : "";
                    return Dialogs.prompt(msg, defText);
                }));

        // Мост в поток интерфейса: единственный правильный способ тронуть окно
        // из потока скрипта. Swing не потокобезопасен, и раньше это было незаметно
        // только из-за замка запуска.
        scope.define("later", BuiltinFunction.of("later",
                Signature.of(Param.required("handler")),
                (context, arguments, span) ->
                        Dialogs.later(arguments.callback(0, "обработчик"), context)));

        scope.define("sync", BuiltinFunction.of("sync",
                Signature.of(Param.required("handler")),
                (context, arguments, span) ->
                        Dialogs.sync(arguments.callback(0, "обработчик"), context, span)));

        // Прежнее имя того же моста: скрипты с ним уже написаны, и ломать их незачем.
        scope.define("runLater", BuiltinFunction.of("runLater",
                Signature.of(Param.required("handler")),
                (context, arguments, span) ->
                        Dialogs.later(arguments.callback(0, "обработчик"), context)));

        scope.define("wait", BuiltinFunction.of("wait", Arity.exactly(0),
                (context, arguments, span) -> {
                    tracker.waitUntilClosed(context);
                    return NullValue.NULL;
                }));

        scope.define("loop", BuiltinFunction.of("loop", Arity.exactly(0),
                (context, arguments, span) -> {
                    tracker.waitUntilClosed(context);
                    return NullValue.NULL;
                }));
    }

    private static Value createBoxPanel(NativeClass panelClass, int axis) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, axis));
        NativeInstance instance = new NativeInstance(panelClass);
        instance.state(panel);
        return instance;
    }
}
