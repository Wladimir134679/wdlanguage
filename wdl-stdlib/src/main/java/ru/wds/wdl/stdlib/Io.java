package ru.wds.wdl.stdlib;

import ru.wds.wdl.embed.Library;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.StringValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Модуль {@code sys.io}: файлы.
 *
 * <pre>{@code
 * import sys.io as io
 *
 * io.write("report.txt", "готово")
 * if (io.exists("report.txt")) {
 *     println(io.read("report.txt"))
 * }
 *
 * for (entry in io.list(".")) {
 *     println(entry)
 * }
 *
 * f = new io.File("data.txt")        // тот же File, что и в std
 * println(f.write("привет").read())
 * }</pre>
 *
 * <b>Две записи одного и того же — и это намеренно.</b> Функция читает файл целиком
 * одной строкой ({@code io.read(path)}), класс нужен там, где с одним файлом работают
 * долго: {@code f.write(...).append(...)} без повторения пути. Класс здесь тот же
 * самый объект, что кладёт в область {@code std}, поэтому {@code f is File} остаётся
 * правдой независимо от того, откуда {@code File} взяли.
 * <p>
 * Кодировка всегда UTF-8 — по той же причине, что и у класса {@code File}: скрипт
 * не должен угадывать кодировку, а движок — зависеть от настроек машины.
 * <p>
 * Песочницы здесь нет и не изображается: модуль даёт доступ к файловой системе
 * процесса целиком. Приложение, которому нужны ограничения, просто не кладёт
 * {@code sys/io} в реестр встроенных модулей — либо кладёт свою реализацию
 * под тем же именем.
 */
public final class Io implements Library {

    private Io() {
    }

    /** Фабрика для реестра встроенных модулей. */
    public static Library library() {
        return new Io();
    }

    @Override
    public String name() {
        return "sys/io";
    }

    @Override
    public Environment installTo(Environment scope) {
        Objects.requireNonNull(scope, "scope");

        scope.define(Files.CLASS.name(), Files.CLASS);
        scope.defineConstant("SEPARATOR", StringValue.of(java.io.File.separator));

        scope.define("read", one("read", (path, span) ->
                StringValue.of(Files.io(span, () ->
                        java.nio.file.Files.readString(path, StandardCharsets.UTF_8)))));

        scope.define("lines", one("lines", (path, span) -> {
            List<String> lines = Files.io(span, () ->
                    java.nio.file.Files.readAllLines(path, StandardCharsets.UTF_8));
            List<Value> values = new ArrayList<>(lines.size());
            lines.forEach(line -> values.add(StringValue.of(line)));
            return ArrayValue.of(values);
        }));

        // Запись возвращает путь, а не null: 'println(io.write(p, text))' и цепочка
        // с ним читаются, а «ничего» никому не нужно.
        scope.define("write", BuiltinFunction.of("write", Arity.exactly(2),
                (context, arguments, span) -> {
                    Path path = Files.pathOf(arguments.get(0), span);
                    String data = arguments.get(1).display();
                    Files.io(span, () -> java.nio.file.Files.writeString(
                            path, data, StandardCharsets.UTF_8));
                    return arguments.get(0);
                }));

        scope.define("append", BuiltinFunction.of("append", Arity.exactly(2),
                (context, arguments, span) -> {
                    Path path = Files.pathOf(arguments.get(0), span);
                    String data = arguments.get(1).display();
                    Files.io(span, () -> java.nio.file.Files.writeString(path, data,
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND));
                    return arguments.get(0);
                }));

        scope.define("exists", one("exists", (path, span) ->
                BoolValue.of(java.nio.file.Files.exists(path))));

        scope.define("isDir", one("isDir", (path, span) ->
                BoolValue.of(java.nio.file.Files.isDirectory(path))));

        scope.define("size", one("size", (path, span) ->
                IntValue.of(Files.io(span, () -> java.nio.file.Files.size(path)))));

        // Удаление отвечает, было ли что удалять: 'io.remove(p)' на несуществующем
        // файле — обычное дело, а не ошибка.
        scope.define("remove", one("remove", (path, span) ->
                BoolValue.of(Files.io(span, () -> java.nio.file.Files.deleteIfExists(path)))));

        scope.define("mkdirs", one("mkdirs", (path, span) -> {
            Files.io(span, () -> java.nio.file.Files.createDirectories(path));
            return StringValue.of(path.toString());
        }));

        // Имена, а не пути: каталог известен вызывающему, а склеить путь он умеет.
        scope.define("list", one("list", (path, span) -> {
            List<Value> names = Files.io(span, () -> {
                try (Stream<Path> entries = java.nio.file.Files.list(path)) {
                    List<Value> collected = new ArrayList<>();
                    entries.sorted().forEach(entry ->
                            collected.add(StringValue.of(entry.getFileName().toString())));
                    return collected;
                }
            });
            return ArrayValue.of(names);
        }));

        scope.define("absolute", one("absolute", (path, span) ->
                StringValue.of(path.toAbsolutePath().normalize().toString())));

        return scope;
    }

    /** Функция от одного пути — таких здесь большинство. */
    private static BuiltinFunction one(String name, PathFunction body) {
        return BuiltinFunction.of(name, Arity.exactly(1), (context, arguments, span) ->
                body.apply(Files.pathOf(arguments.get(0), span), span));
    }

    @FunctionalInterface
    private interface PathFunction {
        Value apply(Path path, Span span);
    }
}
