package ru.wds.wdl.stdlib;

import ru.wds.wdl.embed.Args;
import ru.wds.wdl.embed.Library;
import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Модуль {@code sys.net.http}: запросы по HTTP.
 *
 * <pre>{@code
 * import sys.net.http as http
 * import sys.json as json
 *
 * r = http.get("https://api.example.com/users")
 * if (r.ok()) {
 *     for (user in json.parse(r.body)) {
 *         println(user.name)
 *     }
 * }
 *
 * created = http.post("https://api.example.com/users", json.stringify({name: "Аня"}))
 * any = http.request("https://api.example.com/users/7", {
 *     method: "PATCH",
 *     headers: {"Authorization": "Bearer " + token},
 *     body: json.stringify({name: "Аня"}),
 *     timeout: 5
 * })
 * }</pre>
 *
 * <b>Запрос синхронный.</b> Скрипт ждёт ответа на своей строке — как ждёт чтения
 * файла. Неблокирующая работа — отдельная задача и отдельный механизм: интерпретатор
 * однопоточный, и колбэк из чужого потока входить в него не вправе. Пока этого
 * механизма нет, честнее ждать, чем изображать асинхронность там, где её нет.
 * <p>
 * <b>Клиент один на модуль и создаётся при первом запросе.</b> Соединения тогда
 * переиспользуются между запросами скрипта, а импорт, за которым запроса не последовало,
 * не стоит ни потока, ни сокета. Закрывается клиент вместе с запуском —
 * {@link #close()}.
 * <p>
 * Тело — строка в UTF-8: этого хватает JSON, формам и тексту. Двоичные данные
 * потребуют своего типа значений, и заводить его до появления задачи незачем.
 */
public final class Http implements Library {

    /** Сколько ждать ответа, если скрипт не сказал иного. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Ответ сервера.
     * <p>
     * Всё, что в нём есть, выразимо значениями языка, поэтому всё лежит полями:
     * {@code r.status}, {@code r.body}, {@code r.headers["content-type"]} читаются
     * обычным обращением, печатаются и перебираются без единой строчки здесь.
     * Метод остался ровно один — {@code ok()}, потому что «успех» это диапазон,
     * а не поле.
     */
    private static NativeClass responseClass() {
        return NativeClass.named("Response")
                .field("status")
                .field("body", StringValue.EMPTY)
                .field("headers", new MapValue())
                .field("url", StringValue.EMPTY)
                .method("ok", Arity.exactly(0), (self, context, arguments, span) -> {
                    Value status = self.get("status");
                    long code = status instanceof NumberValue number ? number.asLong() : 0;
                    return BoolValue.of(code >= 200 && code < 300);
                })
                .build();
    }

    /** Класс ответа этого запуска: собирается вместе с модулем, как и всё остальное. */
    private NativeClass responseClass;
    private HttpClient client;

    private Http() {
    }

    /** Фабрика для реестра встроенных модулей. */
    public static Library library() {
        return new Http();
    }

    @Override
    public String name() {
        return "sys/net/http";
    }

    @Override
    public Environment installTo(Environment scope) {
        Objects.requireNonNull(scope, "scope");

        responseClass = responseClass();
        scope.define(responseClass.name(), responseClass);

        scope.define("get", BuiltinFunction.of("get", Arity.between(1, 2),
                (context, arguments, span) -> send("GET", url(arguments), null,
                        options(arguments, 1), context, span)));

        scope.define("post", BuiltinFunction.of("post", Arity.between(2, 3),
                (context, arguments, span) -> send("POST", url(arguments), arguments.at(1),
                        options(arguments, 2), context, span)));

        scope.define("put", BuiltinFunction.of("put", Arity.between(2, 3),
                (context, arguments, span) -> send("PUT", url(arguments), arguments.at(1),
                        options(arguments, 2), context, span)));

        scope.define("delete", BuiltinFunction.of("delete", Arity.between(1, 2),
                (context, arguments, span) -> send("DELETE", url(arguments), null,
                        options(arguments, 1), context, span)));

        // Общая форма: метод и тело приходят опциями. Всё остальное — сокращения к ней.
        scope.define("request", BuiltinFunction.of("request", Arity.between(1, 2),
                (context, arguments, span) -> {
                    MapValue options = options(arguments, 1);
                    String method = text(options, "method", "GET", span).toUpperCase(Locale.ROOT);
                    Value body = options.has("body") ? options.get("body") : null;
                    return send(method, url(arguments), body, options, context, span);
                }));

        return scope;
    }

    /**
     * Закрывает клиента вместе с запуском.
     * <p>
     * Без этого встроенный в приложение движок оставлял бы после каждого скрипта
     * живой пул потоков — незаметно и до тех пор, пока их не станет слишком много.
     */
    @Override
    public void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private HttpClient client() {
        if (client == null) {
            client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return client;
    }

    private Value send(String method, URI uri, Value body, MapValue options,
                       CallContext context, Span span) {
        HttpRequest.BodyPublisher payload = body == null || body == NullValue.NULL
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.display(), StandardCharsets.UTF_8);

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout(options, span))
                .method(method, payload);
        headers(options, request, span);

        HttpResponse<String> response;
        try {
            response = client().send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException timeout) {
            throw new WdlRuntimeError(span, "истекло время ожидания ответа от " + uri);
        } catch (IOException failure) {
            throw new WdlRuntimeError(span, "не удалось выполнить запрос к " + uri + ": "
                    + reason(failure));
        } catch (InterruptedException interrupted) {
            // Флаг восстанавливаем: скрипт остановится, но поток приложения обязан
            // узнать, что его просили прерваться.
            Thread.currentThread().interrupt();
            throw new WdlRuntimeError(span, "запрос к " + uri + " прерван");
        } catch (IllegalArgumentException wrong) {
            throw new WdlRuntimeError(span, "запрос к " + uri + " составлен неверно: "
                    + reason(wrong));
        }

        return responseClass.instantiate(List.of(
                IntValue.of(response.statusCode()),
                StringValue.of(response.body()),
                headersOf(response),
                StringValue.of(uri.toString())), context, span);
    }

    /** Заголовки ответа: имя в нижнем регистре, значения через запятую. */
    private static MapValue headersOf(HttpResponse<String> response) {
        MapValue headers = new MapValue();
        for (Map.Entry<String, List<String>> entry : response.headers().map().entrySet()) {
            headers.put(entry.getKey().toLowerCase(Locale.ROOT),
                    StringValue.of(String.join(", ", entry.getValue())));
        }
        return headers;
    }

    private static void headers(MapValue options, HttpRequest.Builder request, Span span) {
        Value given = options.get("headers");
        if (given == NullValue.NULL) {
            return;
        }
        if (!(given instanceof MapValue headers)) {
            throw new WdlRuntimeError(ErrorKind.TYPE, span,
                    Args.because("http: 'headers'", "ожидался объект", given));
        }
        headers.entries().forEach((name, value) -> {
            try {
                request.header(name.display(), value.display());
            } catch (IllegalArgumentException rejected) {
                // Причин две: часть заголовков ставит сам клиент (Host, Content-Length),
                // а значение обязано быть из ASCII — «секрет» кириллицей туда не влезет.
                // Сообщение JDK английское и про Java, поэтому объясняем сами.
                throw new WdlRuntimeError(span, "http: заголовок '" + name.display()
                        + "' со значением '" + value.display() + "' задать нельзя."
                        + " Имя и значение должны состоять из символов ASCII, а заголовки"
                        + " вроде Host и Content-Length клиент ставит сам");
            }
        });
    }

    /** Адрес запроса — первым аргументом у всех форм. */
    private static URI url(Args arguments) {
        String text = arguments.string(0, "адрес").trim();
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException wrong) {
            throw arguments.bad(0, "адрес", "ожидался разбираемый адрес");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw arguments.bad(0, "адрес", "ожидался адрес, начинающийся с http:// или https://");
        }
        return uri;
    }

    /** Опции запроса: объект или ничего. */
    private static MapValue options(Args arguments, int index) {
        return arguments.object(index, "опции запроса", new MapValue());
    }

    private static Duration timeout(MapValue options, Span span) {
        Value given = options.get("timeout");
        if (given == NullValue.NULL) {
            return DEFAULT_TIMEOUT;
        }
        if (!(given instanceof NumberValue number) || number.asDouble() <= 0) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, Args.because("http: 'timeout'",
                    "ожидались секунды ожидания, положительное число", given));
        }
        return Duration.ofMillis((long) (number.asDouble() * 1000));
    }

    private static String text(MapValue options, String key, String fallback, Span span) {
        Value given = options.get(key);
        if (given == NullValue.NULL) {
            return fallback;
        }
        if (given instanceof StringValue string) {
            return string.value();
        }
        throw new WdlRuntimeError(ErrorKind.TYPE, span,
                Args.because("http: '" + key + "'", "ожидалась строка", given));
    }

    private static String reason(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }
}
