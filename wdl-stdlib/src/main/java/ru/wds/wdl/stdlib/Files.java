package ru.wds.wdl.stdlib;

import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.StringValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Класс {@code File}: работа с файлом из скрипта.
 * <p>
 * Показательный пример встроенного класса. Путь — обычное поле, потому что путь
 * это строка: {@code f.path} читается точкой, попадает в печать и в перебор,
 * и никакого кода в библиотеке для этого не нужно. Отдельного метода
 * {@code path()} поэтому нет и быть не может — <b>поле перекрывает метод</b>,
 * это правило языка, а не ограничение библиотеки.
 * <p>
 * Кодировка всегда UTF-8: скрипт не должен угадывать, в какой кодировке файл,
 * а движок — зависеть от настроек машины, на которой его запустили.
 */
final class Files {

    static final NativeClass CLASS = NativeClass.named("File")
            .field("path")

            .init((self, context, arguments, span) -> {
                // Проверяем сразу: ошибка «путь должен быть строкой» полезнее
                // на создании, чем через десяток строк на первом чтении.
                path(self, span);
                return StringValue.EMPTY;
            })

            .method("read", Arity.exactly(0), (self, context, arguments, span) ->
                    StringValue.of(io(span, () ->
                            java.nio.file.Files.readString(path(self, span), StandardCharsets.UTF_8))))

            .method("lines", Arity.exactly(0), (self, context, arguments, span) -> {
                List<String> lines = io(span, () ->
                        java.nio.file.Files.readAllLines(path(self, span), StandardCharsets.UTF_8));
                List<Value> values = new ArrayList<>(lines.size());
                lines.forEach(line -> values.add(StringValue.of(line)));
                return ArrayValue.of(values);
            })

            // Запись возвращает сам файл, а не null: тогда 'f.write("a").read()'
            // читается одной строкой, а результат «ничего» никому не нужен.
            .method("write", Arity.exactly(1), (self, context, arguments, span) -> {
                String data = arguments.get(0).display();
                io(span, () -> java.nio.file.Files.writeString(
                        path(self, span), data, StandardCharsets.UTF_8));
                return self;
            })

            .method("append", Arity.exactly(1), (self, context, arguments, span) -> {
                String data = arguments.get(0).display();
                io(span, () -> java.nio.file.Files.writeString(path(self, span), data,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                return self;
            })

            .method("exists", Arity.exactly(0), (self, context, arguments, span) ->
                    BoolValue.of(java.nio.file.Files.exists(path(self, span))))

            .method("size", Arity.exactly(0), (self, context, arguments, span) ->
                    IntValue.of(io(span, () -> java.nio.file.Files.size(path(self, span)))))

            .method("remove", Arity.exactly(0), (self, context, arguments, span) ->
                    BoolValue.of(io(span, () -> java.nio.file.Files.deleteIfExists(path(self, span)))))

            .method("name", Arity.exactly(0), (self, context, arguments, span) ->
                    StringValue.of(path(self, span).getFileName().toString()))

            .method("absolute", Arity.exactly(0), (self, context, arguments, span) ->
                    StringValue.of(path(self, span).toAbsolutePath().toString()))

            // Фабрика: способ создания с именем. Тот же приём, что 'fun User.of(...)'
            // в языке, — просто записанный на Java.
            .factory("temp", Arity.between(0, 1), (context, arguments, span) -> {
                String prefix = arguments.isEmpty() ? "wdl" : arguments.get(0).display();
                Path created = io(span, () -> java.nio.file.Files.createTempFile(prefix, ".tmp"));
                // Квалифицированное имя: лямбда выполнится, когда поле уже собрано.
                return Files.CLASS.instantiate(List.of(StringValue.of(created.toString())), context, span);
            })

            .constant("SEPARATOR", StringValue.of(java.io.File.separator))
            .build();

    private Files() {
    }

    /** Путь из поля {@code path}: поле обычное, значит и прочитать его можно обычно. */
    private static Path path(NativeInstance self, Span span) {
        return pathOf(self.get("path"), span);
    }

    /**
     * Путь из значения языка. Отдельно от поля, потому что тем же путём ходят
     * функции модуля {@code sys.io}: {@code io.read("data.txt")} — тот же путь,
     * та же проверка и то же сообщение.
     */
    static Path pathOf(Value value, Span span) {
        try {
            return Path.of(Std.text(value, span, "путь к файлу"));
        } catch (java.nio.file.InvalidPathException e) {
            throw new WdlRuntimeError(span, "недопустимый путь к файлу: " + e.getReason());
        }
    }

    /**
     * Оборачивает ошибку файловой системы в ошибку скрипта.
     * <p>
     * Скрипт живёт в чужом приложении, и {@code IOException} посреди чужого кода
     * ему пользы не принесёт: нужна ошибка с местом в исходнике, которую движок
     * покажет так же, как деление на ноль.
     */
    static <T> T io(Span span, IoAction<T> action) {
        try {
            return action.run();
        } catch (IOException | UncheckedIOException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new WdlRuntimeError(span, "не удалось обратиться к файлу: " + reason);
        }
    }

    static void io(Span span, IoRun action) {
        io(span, () -> {
            action.run();
            return null;
        });
    }

    @FunctionalInterface
    interface IoAction<T> {
        T run() throws IOException;
    }

    @FunctionalInterface
    interface IoRun {
        void run() throws IOException;
    }
}
