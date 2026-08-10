package ru.wds.wdl.stdlib;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.wds.wdl.stdlib.Scripts.errorOf;
import static ru.wds.wdl.stdlib.Scripts.printed;

/**
 * Модуль {@code sys.net.http}.
 * <p>
 * Сервер поднимается свой, на localhost: тест не должен зависеть ни от интернета,
 * ни от чужого сервиса. {@code com.sun.net.httpserver} лежит в JDK, поэтому
 * и зависимости для этого не понадобилось.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HttpTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/hello", exchange -> {
            byte[] body = "{\"name\":\"Аня\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        // Отражает запрос: метод, тело и один заголовок — по ним и видно, что ушло.
        server.createContext("/echo", exchange -> {
            String sent;
            try (InputStream in = exchange.getRequestBody()) {
                sent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String token = exchange.getRequestHeaders().getFirst("X-Token");
            byte[] body = (exchange.getRequestMethod() + "|" + sent + "|" + token)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("get: статус, тело, заголовки ответа и ok()")
    void get() {
        assertEquals("200 true application/json Аня", printed("""
                import sys.net.http as http
                import sys.json as json
                r = http.get("%s/hello")
                println(r.status, " ", r.ok(), " ", r.headers["content-type"], " ",
                        json.parse(r.body).name)
                """.formatted(base)));
    }

    @Test
    @DisplayName("post отправляет тело, опции — заголовки")
    void postWithHeaders() {
        assertEquals("POST|{\"a\":1}|s3cret", printed("""
                import sys.net.http as http
                r = http.post("%s/echo", "{\\"a\\":1}", {headers: {"X-Token": "s3cret"}})
                println(r.body)
                """.formatted(base)));
    }

    @Test
    @DisplayName("заголовок не из ASCII отвергается с объяснением, а не молча")
    void nonAsciiHeaderExplained() {
        String message = errorOf("""
                import sys.net.http as http
                http.get("%s/echo", {headers: {"X-Token": "секрет"}})
                """.formatted(base)).getMessage();
        assertTrue(message.contains("ASCII"), message);
    }

    @Test
    @DisplayName("request: метод и тело приходят опциями")
    void request() {
        assertEquals("PATCH|тело|null", printed("""
                import sys.net.http as http
                r = http.request("%s/echo", {method: "patch", body: "тело"})
                println(r.body)
                """.formatted(base)));
    }

    @Test
    @DisplayName("ответ 404 — это ответ, а не ошибка: решает скрипт")
    void notFoundIsAnAnswer() {
        assertEquals("404 false", printed("""
                import sys.net.http as http
                r = http.get("%s/missing")
                println(r.status, " ", r.ok())
                """.formatted(base)));
    }

    @Test
    @DisplayName("Response — обычный класс: поля читаются, is работает")
    void responseIsOrdinaryClass() {
        assertEquals("object true", printed("""
                import sys.net.http as http
                r = http.get("%s/hello")
                println(typeof(r), " ", r is http.Response)
                """.formatted(base)));
    }

    @Test
    @DisplayName("сервер не отвечает — ошибка скрипта с адресом, а не Java-стек")
    void connectionRefused() {
        // Порт занят нашим сервером, но такого пути нет ни у кого: берём заведомо
        // закрытый порт на локальной машине.
        String message = errorOf("""
                import sys.net.http as http
                http.get("http://127.0.0.1:1/nothing")
                """).getMessage();
        assertTrue(message.contains("не удалось выполнить запрос"), message);
    }

    @Test
    @DisplayName("адрес без схемы отвергается сразу")
    void schemeRequired() {
        assertTrue(errorOf("""
                import sys.net.http as http
                http.get("example.com")
                """).getMessage().contains("должен начинаться с http://"));
    }

    @Test
    @DisplayName("опции проверяются: не объект — ошибка с объяснением")
    void optionsChecked() {
        assertTrue(errorOf("""
                import sys.net.http as http
                http.get("%s/hello", 5)
                """.formatted(base)).getMessage().contains("опции запроса должны быть объектом"));
    }

    @Test
    @DisplayName("timeout — секунды ожидания, и он работает")
    void timeout() throws IOException {
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        assertTrue(errorOf("""
                import sys.net.http as http
                http.get("%s/slow", {timeout: 0.2})
                """.formatted(base)).getMessage().contains("истекло время ожидания"));
    }
}
