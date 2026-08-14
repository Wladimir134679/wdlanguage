package ru.wds.wdl.stdlib;

import ru.wds.wdl.embed.Args;
import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Открытые потоки {@code sys.io}: то, ради чего в языке есть {@code use}.
 * <p>
 * {@code File} ресурсом не является — его {@code read} и {@code write} открывают
 * и закрывают поток внутри, — поэтому закрывать там нечего. А вот {@code io.open}
 * и {@code io.create} отдают <b>живое</b>: дескриптор держится, пока не позовут
 * {@code close()}.
 * <pre>{@code
 * use (src = io.open(from), dst = io.create(to)) {
 *     dst.write(src.read())
 * }                                    // сначала dst.close(), затем src.close()
 * }</pre>
 * <b>Классы собираются на запуск, а не статическим полем</b>, и причина в трейте:
 * {@code Closeable} объявлен прелюдией и принадлежит запуску, поэтому класс, который
 * его обещает, обязан браться из той же области видимости. Это то же самое, из-за
 * чего {@code IndexError} одного запуска — не {@code IndexError} другого.
 */
final class Streams {

    private Streams() {
    }

    /** Имя трейта, который прелюдия кладёт в корневую область. */
    private static final String CLOSEABLE = "Closeable";

    /**
     * Общий родитель потоков: путь и закрытие.
     * <p>
     * Ровно то, что у чтения и записи одинаково, — и ровно то, о чём спрашивают,
     * не зная, что именно открыли: {@code r is io.Stream}. Обещание {@code Closeable}
     * тоже здесь: закрывается поток одинаково, чем бы он ни был.
     */
    static NativeClass stream(Environment scope) {
        return closeable(NativeClass.named("Stream")
                .field("path")
                .method("close", Arity.exactly(0), Streams::close), scope)
                .build();
    }

    /** Класс читающего потока для этого запуска. */
    static NativeClass reader(NativeClass stream) {
        return NativeClass.named("Reader")
                .extending(stream)

                .method("read", Arity.exactly(0), (self, context, arguments, span) ->
                        StringValue.of(Files.io(span, () -> readAll(reader(self, span)))))

                .method("lines", Arity.exactly(0), (self, context, arguments, span) -> {
                    List<Value> lines = new ArrayList<>();
                    Files.io(span, () -> {
                        BufferedReader source = reader(self, span);
                        for (String line = source.readLine(); line != null; line = source.readLine()) {
                            lines.add(StringValue.of(line));
                        }
                    });
                    return ArrayValue.of(lines);
                })

                // Отдельная строка, а не весь файл: ради этого поток и открывают.
                // Конец — это null, а не пустая строка: пустые строки в файле бывают.
                .method("readLine", Arity.exactly(0), (self, context, arguments, span) -> {
                    String line = Files.io(span, () -> reader(self, span).readLine());
                    return line == null ? NullValue.NULL : StringValue.of(line);
                })

                .build();
    }

    /** Класс пишущего потока для этого запуска. */
    static NativeClass writer(NativeClass stream) {
        return NativeClass.named("Writer")
                .extending(stream)

                // Возвращает сам поток, а не null: 'dst.write(a).write(b)' читается,
                // а «ничего» никому не нужно — то же правило, что у File.write.
                .method("write", Arity.exactly(1), (self, context, arguments, span) -> {
                    String data = arguments.at(0).display();
                    Files.io(span, () -> writer(self, span).write(data));
                    return self;
                })

                .method("writeLine", Arity.exactly(1), (self, context, arguments, span) -> {
                    String data = arguments.at(0).display();
                    Files.io(span, () -> {
                        BufferedWriter target = writer(self, span);
                        target.write(data);
                        target.newLine();
                    });
                    return self;
                })

                .build();
    }

    /**
     * Подмешивает {@code Closeable}, если прелюдия в этом запуске выполнялась.
     * <p>
     * Если её нет, класс остаётся обычным: {@code close()} у него всё равно есть,
     * и {@code defer f.close()} работает — не работает только проверка в {@code use}.
     */
    private static NativeClass.Builder closeable(NativeClass.Builder builder, Environment scope) {
        return scope.lookup(CLOSEABLE) instanceof TraitValue trait ? builder.with(trait) : builder;
    }

    /** Открывает файл на чтение. */
    static Value open(Path path, NativeClass reader, CallContext context, Span span) {
        BufferedReader source = Files.io(span, () ->
                java.nio.file.Files.newBufferedReader(path, StandardCharsets.UTF_8));
        return withState(reader, path, source, context, span);
    }

    /** Создаёт файл на запись, затирая прежнее содержимое. */
    static Value create(Path path, NativeClass writer, CallContext context, Span span) {
        BufferedWriter target = Files.io(span, () ->
                java.nio.file.Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING));
        return withState(writer, path, target, context, span);
    }

    /** Открывает файл на дозапись в конец. */
    static Value append(Path path, NativeClass writer, CallContext context, Span span) {
        BufferedWriter target = Files.io(span, () ->
                java.nio.file.Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND));
        return withState(writer, path, target, context, span);
    }

    private static Value withState(NativeClass type, Path path, Closeable live,
                                   CallContext context, Span span) {
        Value instance = type.instantiate(List.of(StringValue.of(path.toString())), context, span);
        ((NativeInstance) instance).state(live);
        return instance;
    }

    /**
     * Закрытие идемпотентно: {@code close()} на уже закрытом потоке — не ошибка.
     * <p>
     * Иначе {@code use} поверх потока, который тело закрыло само, падал бы на выходе,
     * а «закрыл дважды» — не то, о чём стоит спорить с автором скрипта.
     */
    private static Value close(NativeInstance self, CallContext context, Args arguments,
                               Span span) {
        Closeable live = self.state(Closeable.class);
        if (live == null) {
            return BoolValue.FALSE;
        }
        // Приведение обязательно: без него Java выберет state(Class), а не state(Object).
        self.state((Object) null);
        Files.io(span, live::close);
        return BoolValue.TRUE;
    }

    private static BufferedReader reader(NativeInstance self, Span span) {
        return live(self, BufferedReader.class, span);
    }

    private static BufferedWriter writer(NativeInstance self, Span span) {
        return live(self, BufferedWriter.class, span);
    }

    private static <T> T live(NativeInstance self, Class<T> type, Span span) {
        T state = self.state(type);
        if (state == null) {
            throw new WdlRuntimeError(span, "поток уже закрыт: "
                    + "после close() читать и писать нечем");
        }
        return state;
    }

    private static String readAll(BufferedReader source) throws IOException {
        StringBuilder sb = new StringBuilder(1024);
        char[] buffer = new char[8192];
        for (int read = source.read(buffer); read > 0; read = source.read(buffer)) {
            sb.append(buffer, 0, read);
        }
        return sb.toString();
    }
}
