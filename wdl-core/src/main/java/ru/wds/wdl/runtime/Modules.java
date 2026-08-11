package ru.wds.wdl.runtime;

import ru.wds.wdl.embed.Library;
import ru.wds.wdl.module.ModuleKey;
import ru.wds.wdl.module.ModuleUnits;
import ru.wds.wdl.module.NativeModules;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.types.ModuleValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Выполненные модули одного запуска: ключ → значение.
 * <p>
 * Разбором здесь не занимаются — он на стадию раньше, в {@link ModuleUnits}. Отсюда
 * берётся вторая половина: файл выполняется в своей области, и получившееся значение
 * достаётся всем, кто его импортировал.
 * <p>
 * <b>Модуль выполняется один раз за запуск.</b> Это не оптимизация, а решение о смысле:
 * иначе {@code import} внутри функции выполнял бы файл на каждом вызове, а два импорта
 * одного модуля давали бы два разных набора значений — и класс, полученный из первого,
 * не был бы классом из второго.
 * <p>
 * Отсюда же и круг: если модуль через цепочку импортов просит сам себя, отдавать
 * нечего — он ещё не выполнен. При разборе круг ошибкой не считается (там достаточно
 * не дать типов), а здесь считается: значения без выполнения не бывает.
 *
 * <h2>Два вида модулей и два пространства имён</h2>
 * Модуль бывает файлом и бывает {@link NativeModules встроенным} — написанным на Java.
 * Отличаются они одним: <b>встроенный ищется по имени, файл — по пути</b>. Поэтому
 * и реестра выполненных здесь два: {@code import sys.json} из любого файла означает
 * один и тот же встроенный модуль, а {@code import "./sys/json"} — файл, чей ключ
 * зависит от того, откуда его попросили. Свести их в одну таблицу нельзя: ключ
 * {@code sys/json} тогда означал бы то одно, то другое.
 * <p>
 * Круг встроенному модулю не грозит: он ничего не выполняет — только кладёт имена.
 */
final class Modules {

    private final ModuleUnits units;
    private final NativeModules natives;
    /** Корневая область запуска: родитель областей всех модулей. */
    private final Environment root;
    private final Map<String, ModuleValue> values = new HashMap<>();
    /** Выполненные встроенные модули: своё пространство имён, см. javadoc класса. */
    private final Map<String, ModuleValue> installed = new HashMap<>();
    /** Библиотеки в порядке создания — их же закрывать в обратном. */
    private final List<Library> opened = new ArrayList<>();
    /** Модули, которые выполняются прямо сейчас, — по ним и виден круг. */
    private final Deque<String> running = new ArrayDeque<>();

    Modules(ModuleUnits units, NativeModules natives, Environment root) {
        this.units = Objects.requireNonNull(units, "units");
        this.natives = Objects.requireNonNull(natives, "natives");
        this.root = Objects.requireNonNull(root, "root");
    }

    ModuleUnits units() {
        return units;
    }

    NativeModules natives() {
        return natives;
    }

    /**
     * Значение модуля: готовое из реестра, установленное библиотекой или полученное
     * выполнением файла.
     * <p>
     * Порядок поиска — встроенные, потом файлы, — и он же правило языка: имя ищется
     * как имя, а путь как путь. Явный путь ({@code "./x"}, {@code "/x"}) первый шаг
     * пропускает: у встроенного модуля каталога нет, а значит, и разговора о нём тут нет.
     *
     * @param path путь так, как он написан в инструкции
     * @param home каталог импортирующего файла
     * @param span место в скрипте, которому принадлежит вопрос
     */
    ModuleValue load(String path, String home, Span span, ExecutionContext context,
                     Interpreter interpreter) {
        if (path.isEmpty()) {
            throw new WdlRuntimeError(ErrorKind.IMPORT, span, "пустой путь модуля");
        }
        String name = ModuleKey.isExplicitPath(path) ? null : ModuleKey.name(path);
        if (name != null) {
            ModuleValue installedModule = nativeModule(name, span);
            if (installedModule != null) {
                return installedModule;
            }
        }
        return fileModule(ModuleKey.resolve(path, home), name, span, context, interpreter);
    }

    /** Закрывает библиотеки встроенных модулей — в порядке, обратном созданию. */
    void shutdown() {
        for (int i = opened.size() - 1; i >= 0; i--) {
            Library library = opened.get(i);
            try {
                library.close();
            } catch (RuntimeException | LinkageError failure) {
                // Запуск уже отработал, и ронять его закрытием нечестно: то, что скрипт
                // напечатал, он напечатал. Сообщить, впрочем, надо — молчаливо потерянный
                // ресурс хуже громкой строки в логе.
                System.getLogger(Modules.class.getName())
                        .log(System.Logger.Level.WARNING,
                                "модуль '" + library.name() + "' не закрылся", failure);
            }
        }
        opened.clear();
        installed.clear();
    }

    /** Встроенный модуль или {@code null}, если такого имени среди них нет. */
    private ModuleValue nativeModule(String name, Span span) {
        ModuleValue ready = installed.get(name);
        if (ready != null) {
            return ready;
        }

        Library library;
        try {
            library = natives.find(name);
        } catch (RuntimeException | LinkageError failure) {
            // Самая частая причина — библиотеки нет в classpath. Java-стек автору
            // скрипта бесполезен: ему нужна строка, на которой он попросил модуль.
            throw new WdlRuntimeError(ErrorKind.IMPORT, span, "встроенный модуль '" + name
                    + "' не удалось подготовить: " + reason(failure));
        }
        if (library == null) {
            return null;
        }

        ModuleScope scope = new ModuleScope(name, root);
        try {
            library.installTo(scope);
        } catch (WdlError error) {
            throw error;
        } catch (RuntimeException | LinkageError failure) {
            throw new WdlRuntimeError(ErrorKind.IMPORT, span, "встроенный модуль '" + name
                    + "' не удалось подготовить: " + reason(failure));
        }
        opened.add(library);
        ModuleValue module = scope.module();
        installed.put(name, module);
        return module;
    }

    /**
     * @param name имя, под которым модуль уже искали среди встроенных, или {@code null},
     *             если путь написан явным ({@code "./x"}). Нужно только сообщению:
     *             «не найден» без «а где искали» заставляет гадать
     */
    private ModuleValue fileModule(String key, String name, Span span, ExecutionContext context,
                                   Interpreter interpreter) {
        ModuleValue ready = values.get(key);
        if (ready != null) {
            return ready;
        }
        if (running.contains(key)) {
            throw new WdlRuntimeError(ErrorKind.IMPORT, span, "циклический импорт: "
                    + String.join(" → ", running) + " → " + key
                    + ". Модуль не может пользоваться тем, что ещё не выполнено");
        }

        ModuleUnits.Loaded loaded = units.load(key);
        if (!loaded.ok()) {
            if (loaded.problem() == null) {
                throw new WdlRuntimeError(ErrorKind.IMPORT, span,
                        "модуль '" + key + "' разбирается прямо сейчас: круг в импортах");
            }
            // Про встроенные упоминаем, только если они в этом запуске вообще есть:
            // иначе подсказка отправляла бы искать там, куда никто ничего не клал.
            boolean tellAboutNatives = loaded.missing() && name != null
                    && natives != NativeModules.NONE;
            throw new WdlRuntimeError(ErrorKind.IMPORT, span, tellAboutNatives
                    ? loaded.problem() + ", и встроенного модуля '" + name + "' тоже нет"
                    : loaded.problem());
        }

        running.addLast(key);
        try {
            ModuleValue module = execute(loaded.unit(), context, interpreter);
            values.put(key, module);
            return module;
        } finally {
            running.removeLast();
        }
    }

    private ModuleValue execute(Unit unit, ExecutionContext context, Interpreter interpreter) {
        ModuleScope scope = new ModuleScope(unit.key(), root);
        try {
            // Область модуля уже создана и вложенной быть не должна: имена файла
            // обязаны лечь именно в неё — она же и есть значение модуля.
            interpreter.run(unit.program(), context.withScope(scope).withUnit(unit));
        } catch (WdlError error) {
            throw error.inSource(unit.source());
        }
        return scope.module();
    }

    private static String reason(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }
}
