package ru.wds.wdl.runtime;

import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.InstanceObjectValue;

import java.util.List;
import java.util.Objects;

/**
 * Ошибка выполнения скрипта: деление на ноль, неверный тип, выход за границы —
 * и всё, что бросил сам скрипт через {@code throw}.
 * <p>
 * У каждой такой ошибки есть <b>класс</b> в иерархии {@code Exception}, и на этом
 * держится {@code catch}: {@code catch (e is IndexError)} ловит одно, {@code catch (e
 * is RuntimeError)} — всё, что бросает движок, {@code catch (e)} — вообще всё ловимое.
 * Класс берётся из двух разных мест, и различает их {@link #payload()}:
 * <ul>
 *   <li>ошибку бросил движок — класс задан {@link ErrorKind}, а значение-экземпляр
 *       не создаётся вовсе, пока ошибку не поймают. Непойманная ошибка доходит
 *       до хозяина запуска, ни разу не став объектом;</li>
 *   <li>ошибку бросил скрипт — значение уже есть, оно и лежит в {@code payload}.
 *       Тот же самый объект получит обработчик: {@code throw e} внутри {@code catch}
 *       не порождает второй ошибки.</li>
 * </ul>
 * Что нельзя поймать, здесь не живёт: прерывание потока, исчерпание стека
 * и слишком глубокая рекурсия — это {@link FatalError}.
 */
public final class WdlRuntimeError extends WdlError {

    private final ErrorKind kind;
    /**
     * Значение ошибки: у брошенной скриптом оно есть с самого начала, у ошибки движка
     * появляется только тогда, когда её действительно поймали.
     * <p>
     * Записывается один раз — обработчик обязан получить <b>тот самый</b> объект,
     * иначе {@code throw e} внутри {@code catch} порождал бы вторую ошибку вместо первой.
     */
    private transient Value payload;
    /**
     * Путь по скрипту, собранный из кадров вызова в момент броска.
     * <p>
     * Поле записывается один раз и не участвует в создании ошибки: место броска знает
     * тот, кто бросает, а цепочку вызовов — ближайшая граница вызова, через которую
     * ошибка полетит наружу. Ошибка — объект одноразовый, и копировать её целиком ради
     * дописанного о ней знания незачем.
     */
    private transient List<String> trace;
    /**
     * Исходное исключение Java, если ошибка прилетела из библиотеки, — иначе {@code null}.
     * <p>
     * Стек у него уже собран, и выбрасывать эту информацию нельзя: тому, кто чинит
     * библиотеку, нужна именно она. Скрипту он отдаётся методом {@code javaTrace()},
     * а не полем, — см. {@link ErrorKind#JAVA}.
     */
    private final transient Throwable javaCause;
    /** Модуль, из которого прилетело чужое исключение, или {@code null}. */
    private final transient String module;

    public WdlRuntimeError(Span span, String message) {
        this(span, message, ErrorKind.RUNTIME, null, null, null, null, null);
    }

    /** Ошибка движка с указанным классом: {@code new WdlRuntimeError(ErrorKind.INDEX, span, ...)}. */
    public WdlRuntimeError(ErrorKind kind, Span span, String message) {
        this(span, message, Objects.requireNonNull(kind, "kind"), null, null, null, null, null);
    }

    private WdlRuntimeError(Span span, String message, ErrorKind kind, Value payload,
                            Source source, List<String> trace, Throwable javaCause, String module) {
        super(message, span, source);
        this.kind = kind;
        this.payload = payload;
        this.trace = trace;
        this.javaCause = javaCause;
        this.module = module;
    }

    /**
     * Чужое исключение, пойманное на границе вызова.
     * <p>
     * Оборачивается всё, что прилетело из Java и не стало ошибкой скрипта само:
     * приложение вправе положить в область видимости что угодно, и для автора скрипта
     * это не должно выглядеть как крах движка.
     *
     * @param module имя модуля, из которого прилетело, или {@code null}, если оно неизвестно
     */
    static WdlRuntimeError fromJava(Span span, Throwable cause, String module) {
        Objects.requireNonNull(cause, "cause");
        String message = cause.getMessage();
        return new WdlRuntimeError(span,
                message == null || message.isBlank() ? cause.getClass().getName() : message,
                ErrorKind.JAVA, null, null, null, cause, module);
    }

    /** Исходное исключение Java или {@code null}. */
    public Throwable javaCause() {
        return javaCause;
    }

    /** Модуль, из которого прилетело чужое исключение, или {@code null}. */
    public String module() {
        return module;
    }

    /**
     * Ошибка, брошенная скриптом: значение уже создано, класс берётся у него.
     * <p>
     * Сообщение достаётся из поля {@code message} самого значения — чтобы хозяин
     * запуска, который об иерархии классов скрипта ничего не знает, всё равно получил
     * читаемую строку.
     */
    static WdlRuntimeError thrown(Span span, Value error, String message) {
        return new WdlRuntimeError(span, message, null, Objects.requireNonNull(error, "error"),
                null, null, null, null);
    }

    /** Класс ошибки движка или {@code null}, если ошибку бросил скрипт своим классом. */
    public ErrorKind kind() {
        return kind;
    }

    /** Значение ошибки, если оно уже создано, иначе {@code null}. */
    public Value payload() {
        return payload;
    }

    /**
     * Запоминает созданное значение ошибки.
     * <p>
     * Зовёт интерпретатор, когда для ошибки движка нашёлся обработчик: до этого момента
     * объект не нужен никому, а непойманная ошибка не материализуется никогда — на пути
     * в хост от неё требуется только текст и место.
     */
    void materialized(Value value) {
        if (payload == null) {
            payload = Objects.requireNonNull(value, "value");
        }
    }

    @Override
    public String kindName() {
        if (payload instanceof InstanceObjectValue instance) {
            return instance.owner().name();
        }
        return kind == null ? null : kind.title();
    }

    @Override
    public List<String> trace() {
        return trace == null ? List.of() : trace;
    }

    /**
     * Запоминает путь по скрипту — но только если он ещё не запомнен.
     * <p>
     * Спрашивают об этом все границы вызова на пути наружу, а ответ нужен от самой
     * внутренней: цепочка кадров у неё полная, а у внешних от неё остался бы хвост.
     */
    void rememberTrace(List<String> frames) {
        if (trace == null && !frames.isEmpty()) {
            trace = List.copyOf(frames);
        }
    }

    @Override
    public WdlRuntimeError inSource(Source known) {
        if (source() != null || known == null) {
            return this;
        }
        return new WdlRuntimeError(span(), getMessage(), kind, payload, known, trace,
                javaCause, module);
    }
}
