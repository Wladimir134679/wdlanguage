package ru.wds.wdl.stdlib.gui;

import ru.wds.wdl.embed.Callback;
import ru.wds.wdl.embed.Library;
import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.NullValue;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import java.util.Objects;

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
public final class Gui implements Library {

    private final WindowTracker tracker = new WindowTracker();

    private Gui() {
    }

    public static Library library() {
        return new Gui();
    }

    @Override
    public String name() {
        return "sys/gui";
    }

    @Override
    public Environment installTo(Environment scope) {
        Objects.requireNonNull(scope, "scope");

        NativeClass layout = Layouts.layoutClass(scope);
        NativeClass window = NativeWindow.in(scope, tracker);
        NativeClass panel = NativePanel.in(scope);
        NativeClass button = NativeButton.in(scope);
        NativeClass label = NativeLabel.in(scope);
        NativeClass textField = NativeTextField.in(scope);
        NativeClass textArea = NativeTextArea.in(scope);
        NativeClass checkBox = NativeCheckBox.in(scope);
        NativeClass comboBox = NativeComboBox.in(scope);

        scope.define(layout.name(), layout);
        scope.define(window.name(), window);
        scope.define(panel.name(), panel);
        scope.define(button.name(), button);
        scope.define(label.name(), label);
        scope.define(textField.name(), textField);
        scope.define(textArea.name(), textArea);
        scope.define(checkBox.name(), checkBox);
        scope.define(comboBox.name(), comboBox);

        // Фабрики компоновщиков
        scope.define("flow", BuiltinFunction.of("flow", Arity.between(0, 3),
                (context, arguments, span) -> {
                    String align = arguments.size() > 0 ? arguments.string(0, "выравнивание") : "center";
                    int hgap = (int) arguments.integer(1, "горизонтальный отступ", 5);
                    int vgap = (int) arguments.integer(2, "вертикальный отступ", 5);
                    return Layouts.createFlow(layout, align, hgap, vgap);
                }));

        scope.define("border", BuiltinFunction.of("border", Arity.between(0, 2),
                (context, arguments, span) -> {
                    int hgap = (int) arguments.integer(0, "горизонтальный отступ", 0);
                    int vgap = (int) arguments.integer(1, "вертикальный отступ", 0);
                    return Layouts.createBorder(layout, hgap, vgap);
                }));

        scope.define("grid", BuiltinFunction.of("grid", Arity.between(2, 4),
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
        scope.define("alert", BuiltinFunction.of("alert", Arity.between(1, 2),
                (context, arguments, span) -> {
                    String msg = arguments.at(0).display();
                    String title = arguments.size() > 1 ? arguments.string(1, "заголовок") : "Информация";
                    return Dialogs.alert(msg, title);
                }));

        scope.define("confirm", BuiltinFunction.of("confirm", Arity.between(1, 2),
                (context, arguments, span) -> {
                    String msg = arguments.at(0).display();
                    String title = arguments.size() > 1 ? arguments.string(1, "заголовок") : "Подтверждение";
                    return Dialogs.confirm(msg, title);
                }));

        scope.define("prompt", BuiltinFunction.of("prompt", Arity.between(1, 2),
                (context, arguments, span) -> {
                    String msg = arguments.at(0).display();
                    String defText = arguments.size() > 1 ? arguments.string(1, "текст по умолчанию") : "";
                    return Dialogs.prompt(msg, defText);
                }));

        // Мост в поток интерфейса: единственный правильный способ тронуть окно
        // из потока скрипта. Swing не потокобезопасен, и раньше это было незаметно
        // только из-за замка запуска.
        scope.define("later", BuiltinFunction.of("later", Arity.exactly(1),
                (context, arguments, span) ->
                        Dialogs.later(arguments.callback(0, "обработчик"), context)));

        scope.define("sync", BuiltinFunction.of("sync", Arity.exactly(1),
                (context, arguments, span) ->
                        Dialogs.sync(arguments.callback(0, "обработчик"), context, span)));

        // Прежнее имя того же моста: скрипты с ним уже написаны, и ломать их незачем.
        scope.define("runLater", BuiltinFunction.of("runLater", Arity.exactly(1),
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

        return scope;
    }

    @Override
    public void close() {
        // Завершение работы модуля: блокирует завершение процесса до тех пор,
        // пока все открытые окна GUI не будут закрыты пользователем.
        tracker.waitUntilClosed();
    }

    private static Value createBoxPanel(NativeClass panelClass, int axis) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, axis));
        NativeInstance instance = new NativeInstance(panelClass);
        instance.state(panel);
        return instance;
    }
}
