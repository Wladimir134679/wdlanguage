package ru.wds.wdl.stdlib;

import ru.wds.wdl.module.Library;
import ru.wds.wdl.bridge.Module;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.runtime.BuiltinFunction;
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
public final class Io {

    private Io() {
    }

    /**
     * Библиотека модуля: типы, константа и четырнадцать функций — одним объявлением.
     * <p>
     * Раньше здесь стоял ручной {@code installTo} на восемнадцать {@code define}
     * подряд: собрать класс, спросить область, положить под своим именем, повторить.
     * Ровно ту работу и делает построитель, и делает её одинаково для всех модулей —
     * а восемнадцать одинаковых строк, написанных руками, только и умеют, что
     * разойтись между собой.
     * <p>
     * Классы потоков стоят после {@code File} и берут его из области: порядок
     * объявления здесь — это порядок установки.
     */
    public static Library library() {
        return Module.named("sys/io")
                // Тот же класс File, что кладёт std, если std в этом запуске установлен:
                // область модуля стоит на корне запуска, значит он там уже есть.
                .type("File", scope -> Files.build())
                .constant("SEPARATOR", StringValue.of(java.io.File.separator))

                // Классы потоков собираются на запуск, а не статическим полем: они
                // обещают трейт Closeable, а он объявлен прелюдией и принадлежит запуску.
                // Общее у чтения и записи вынесено в родителя — и путь, и close(), и само
                // обещание Closeable, — поэтому 'r is io.Stream' отвечает, не спрашивая,
                // что именно открыли.
                .type("Stream", Streams::stream)
                .type("Reader", scope -> Streams.reader(Module.typeIn(scope, "Stream")))
                .type("Writer", scope -> Streams.writer(Module.typeIn(scope, "Stream")))

                // Открытый поток — то, ради чего в языке есть use: дескриптор держится,
                // пока не позовут close(), в отличие от read/write, которые всё делают внутри.
                .install(scope -> {
                    NativeClass reader = Module.typeIn(scope, "Reader");
                    NativeClass writer = Module.typeIn(scope, "Writer");
                    scope.define("open", BuiltinFunction.of("open", Arity.exactly(1),
                            (context, arguments, span) ->
                                    Streams.open(Files.pathOf(arguments, 0), reader, context, span)));
                    scope.define("create", BuiltinFunction.of("create", Arity.exactly(1),
                            (context, arguments, span) ->
                                    Streams.create(Files.pathOf(arguments, 0), writer, context, span)));
                    scope.define("appendTo", BuiltinFunction.of("appendTo", Arity.exactly(1),
                            (context, arguments, span) ->
                                    Streams.append(Files.pathOf(arguments, 0), writer, context, span)));
                })

                .function("read", Arity.exactly(1), path((path, span) ->
                        StringValue.of(Files.io(span, () ->
                                java.nio.file.Files.readString(path, StandardCharsets.UTF_8)))))

                .function("lines", Arity.exactly(1), path((path, span) -> {
                    List<String> lines = Files.io(span, () ->
                            java.nio.file.Files.readAllLines(path, StandardCharsets.UTF_8));
                    List<Value> values = new ArrayList<>(lines.size());
                    lines.forEach(line -> values.add(StringValue.of(line)));
                    return ArrayValue.of(values);
                }))

                // Запись возвращает путь, а не null: 'println(io.write(p, text))' и цепочка
                // с ним читаются, а «ничего» никому не нужно.
                .function("write", Arity.exactly(2), (context, arguments, span) -> {
                    Path path = Files.pathOf(arguments, 0);
                    String data = arguments.at(1).display();
                    Files.io(span, () -> java.nio.file.Files.writeString(
                            path, data, StandardCharsets.UTF_8));
                    return arguments.get(0);
                })

                .function("append", Arity.exactly(2), (context, arguments, span) -> {
                    Path path = Files.pathOf(arguments, 0);
                    String data = arguments.at(1).display();
                    Files.io(span, () -> java.nio.file.Files.writeString(path, data,
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND));
                    return arguments.get(0);
                })

                .function("exists", Arity.exactly(1), path((path, span) ->
                        BoolValue.of(java.nio.file.Files.exists(path))))

                .function("isDir", Arity.exactly(1), path((path, span) ->
                        BoolValue.of(java.nio.file.Files.isDirectory(path))))

                .function("size", Arity.exactly(1), path((path, span) ->
                        IntValue.of(Files.io(span, () -> java.nio.file.Files.size(path)))))

                // Удаление отвечает, было ли что удалять: 'io.remove(p)' на несуществующем
                // файле — обычное дело, а не ошибка.
                .function("remove", Arity.exactly(1), path((path, span) ->
                        BoolValue.of(Files.io(span, () -> java.nio.file.Files.deleteIfExists(path)))))

                .function("mkdirs", Arity.exactly(1), path((path, span) -> {
                    Files.io(span, () -> java.nio.file.Files.createDirectories(path));
                    return StringValue.of(path.toString());
                }))

                // Имена, а не пути: каталог известен вызывающему, а склеить путь он умеет.
                .function("list", Arity.exactly(1), path((path, span) -> {
                    List<Value> names = Files.io(span, () -> {
                        try (Stream<Path> entries = java.nio.file.Files.list(path)) {
                            List<Value> collected = new ArrayList<>();
                            entries.sorted().forEach(entry ->
                                    collected.add(StringValue.of(entry.getFileName().toString())));
                            return collected;
                        }
                    });
                    return ArrayValue.of(names);
                }))

                .function("absolute", Arity.exactly(1), path((path, span) ->
                        StringValue.of(path.toAbsolutePath().normalize().toString())))

                .build();
    }

    /** Тело функции от одного пути — таких здесь большинство. */
    private static BuiltinFunction.Body path(PathFunction body) {
        return (context, arguments, span) -> body.apply(Files.pathOf(arguments, 0), span);
    }

    @FunctionalInterface
    private interface PathFunction {
        Value apply(Path path, Span span);
    }
}
