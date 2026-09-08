package ru.wds.wdl.dap;

import ru.wds.wdl.api.Stdlib;
import ru.wds.wdl.debug.SuspendPolicy;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Что клиент попросил запустить: разбор аргументов {@code launch} и {@code attach}.
 * <p>
 * В протоколе это свободная карта: DAP не описывает, из чего состоит запуск, — у
 * каждого языка своё. Наши поля — ровно те, что есть у консольного {@code wdl}:
 * файл, корень импортов, аргументы скрипта, набор библиотеки, пределы. Иначе отладка
 * запускала бы <b>не то</b>, что запуск, и разница объяснялась бы каждый раз заново.
 * <p>
 * Чего здесь нет намеренно — рабочего каталога: его задаёт тот, кто запустил процесс
 * адаптера, потому что сменить его у живой JVM нельзя, а обещать поле, которое
 * не работает, хуже, чем не иметь его.
 *
 * @param program     файл {@code .wdl} или каталог с {@code main.wdl}
 * @param projectRoot корень файловых импортов; {@code null} — каталог самого скрипта
 * @param arguments   аргументы скрипта, видны ему как {@code args}
 * @param stopOnEntry встать до первой инструкции
 * @param stopOnError встать на ошибке выполнения, до раскрутки
 * @param stdlib      что из библиотеки доступно скрипту
 * @param policy      кого останавливать при срабатывании точки
 * @param maxSteps    предел шагов; {@code 0} — без предела
 * @param timeout     предел времени; {@code null} — без предела
 * @param maxThreads  квота потоков скрипта; {@code 0} — без квоты
 * @param evalTimeout сколько ждать вычисления в кадре, мс; {@code 0} — как у сессии
 */
record LaunchOptions(Path program, Path projectRoot, List<String> arguments,
                     boolean stopOnEntry, boolean stopOnError, Stdlib stdlib,
                     SuspendPolicy policy, long maxSteps, Duration timeout,
                     int maxThreads, long evalTimeout) {

    /**
     * Разбирает карту запроса.
     *
     * @param needsProgram нужен ли файл: у {@code launch} — да, у {@code attach} скрипт
     *                     уже работает и запускать нечего
     * @throws DapError если запрос не выполнить: нет файла, нет такого набора библиотеки
     */
    static LaunchOptions of(Map<String, Object> request, boolean needsProgram) {
        Map<String, Object> arguments = request == null ? Map.of() : request;
        return new LaunchOptions(
                program(arguments, needsProgram),
                directory(arguments, "projectRoot"),
                strings(arguments, "args"),
                flag(arguments, "stopOnEntry"),
                flag(arguments, "stopOnError"),
                stdlib(arguments),
                policy(arguments),
                (long) number(arguments, "maxSteps"),
                seconds(arguments, "timeout"),
                (int) number(arguments, "maxThreads"),
                (long) number(arguments, "evalTimeout"));
    }

    /**
     * Файл, который будет выполнен: каталог превращается в {@code main.wdl} внутри
     * него — так же, как это делает консольный {@code wdl}.
     */
    private static Path program(Map<String, Object> arguments, boolean required) {
        String value = text(arguments, "program");
        if (value == null) {
            if (required) {
                throw new DapError("в запросе launch нет поля program — "
                        + "нечего запускать");
            }
            return null;
        }
        Path path = path(value, "program");
        if (Files.isDirectory(path)) {
            path = path.resolve("main.wdl");
        }
        if (!Files.isRegularFile(path)) {
            throw new DapError("файл не найден: " + path);
        }
        return path;
    }

    private static Path directory(Map<String, Object> arguments, String key) {
        String value = text(arguments, key);
        if (value == null) {
            return null;
        }
        Path path = path(value, key);
        if (!Files.isDirectory(path)) {
            throw new DapError("не каталог: " + path);
        }
        return path;
    }

    private static Path path(String value, String key) {
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException notAPath) {
            throw new DapError("некорректный путь в поле " + key + ": " + value);
        }
    }

    private static Stdlib stdlib(Map<String, Object> arguments) {
        String value = text(arguments, "stdlib");
        if (value == null) {
            // Отлаживают то же, что запускают: у консольного wdl набор полный.
            return Stdlib.STANDARD;
        }
        try {
            return Stdlib.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new DapError("неизвестный набор библиотеки: " + value
                    + " (ожидается none, safe или standard)");
        }
    }

    private static SuspendPolicy policy(Map<String, Object> arguments) {
        String value = text(arguments, "suspend");
        if (value == null) {
            return SuspendPolicy.ALL;
        }
        try {
            return SuspendPolicy.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new DapError("неизвестная политика остановки: " + value
                    + " (ожидается all или thread)");
        }
    }

    private static Duration seconds(Map<String, Object> arguments, String key) {
        double value = number(arguments, key);
        return value <= 0 ? null : Duration.ofNanos((long) (value * 1_000_000_000L));
    }

    private static String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean flag(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * Число из JSON.
     * <p>
     * Приходит оно {@code Double} — в JSON других чисел нет, — и превращать его
     * в {@code long} обязан тот, кто знает смысл поля.
     */
    private static double number(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number known) {
            return known.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException notANumber) {
            throw new DapError("в поле " + key + " ожидалось число, пришло: " + value);
        }
    }

    private static List<String> strings(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new DapError("в поле " + key + " ожидался список строк");
        }
        List<String> strings = new ArrayList<>(list.size());
        for (Object item : list) {
            strings.add(String.valueOf(item));
        }
        return List.copyOf(strings);
    }
}
