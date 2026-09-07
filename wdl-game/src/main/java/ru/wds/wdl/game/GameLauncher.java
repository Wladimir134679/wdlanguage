package ru.wds.wdl.game;

import ru.wds.wdl.api.Stdlib;
import ru.wds.wdl.api.WdlEngine;
import ru.wds.wdl.api.WdlException;
import ru.wds.wdl.runtime.Output;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Запуск игры: {@code wdgame examples/game/pong.wdl}.
 * <p>
 * Приложение здесь — <b>движок</b>, а не язык: он собирает {@link WdlEngine},
 * кладёт рядом со стандартной библиотекой свой модуль {@code game} и отдаёт
 * управление скрипту. Ровно так же поступила бы чужая игра, решившая, что моды
 * к ней пишут на wdl, — и в этом весь смысл модуля: показать встраивание с той
 * стороны, с которой его видит хозяин.
 *
 * <pre>{@code
 * ./gradlew :wdl-game:run --args="examples/game/pong.wdl"
 * ./gradlew :wdl-game:installDist   # → wdl-game/build/install/wdgame/bin/wdgame
 * }</pre>
 *
 * Без аргументов запускается пример из репозитория ({@code examples/game/pong.wdl}):
 * запуск без параметров — это «Run» из IDE, и показывать в такой момент справку
 * вместо игры значит не показать ничего.
 */
public final class GameLauncher {

    /** Ошибка в самом скрипте. */
    private static final int EXIT_SCRIPT_ERROR = 1;
    /** Ошибка в том, как запустили: нет файла, не указан путь. */
    private static final int EXIT_USAGE_ERROR = 2;

    private GameLauncher() {
    }

    public static void main(String[] arguments) {
        // Кодировку вывода задаём сами, по той же причине, что и консольный wdl:
        // JVM-аргументы задачи Gradle не достаются запуску из IDE, а русский текст
        // без этого превращается в мусор.
        PrintStream console = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.setOut(console);
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        int code = run(arguments, console);
        // Явный выход: игра поднимала Swing, а его поток обработки событий
        // не даёт процессу закончиться сам по себе.
        System.exit(code);
    }

    private static int run(String[] arguments, PrintStream console) {
        if (arguments.length > 0 && isHelp(arguments[0])) {
            usage(console);
            return 0;
        }
        Path script;
        if (arguments.length == 0) {
            // Запуск без аргументов — это «Run» из IDE, а не забытый параметр:
            // движок в такой момент обязан показать себя, а не справку. Игра
            // берётся из репозитория, а не из ресурсов jar: второй копии
            // pong.wdl быть не должно — она разойдётся с первой.
            script = demoGame();
            if (script == null) {
                usage(console);
                return EXIT_USAGE_ERROR;
            }
            console.println("Скрипт не указан — запускаю пример: " + script);
        } else {
            try {
                script = Path.of(arguments[0]);
            } catch (InvalidPathException wrong) {
                System.err.println("Не путь к скрипту: " + arguments[0]);
                return EXIT_USAGE_ERROR;
            }
        }
        if (!Files.isRegularFile(script)) {
            System.err.println("Файл игры не найден: " + script.toAbsolutePath());
            return EXIT_USAGE_ERROR;
        }

        WdlEngine engine = WdlEngine.builder()
                // Стандартная библиотека — потому что игра пишется как обычный скрипт:
                // ей нужны и математика, и Random, и sys.io для рекордов.
                .stdlib(Stdlib.STANDARD)
                .output(Output.standard())
                .module(Games.NAME, Games::library)
                .build();
        try {
            engine.run(script);
            return 0;
        } catch (WdlException failed) {
            // Разбор и выполнение оба приходят сюда: сообщение уже собрано движком —
            // с местом в исходнике и путём по скрипту.
            System.err.println(failed.getMessage());
            return EXIT_SCRIPT_ERROR;
        }
    }

    private static boolean isHelp(String argument) {
        return "-h".equals(argument) || "--help".equals(argument);
    }

    /**
     * Пример игры, лежащий в репозитории, или {@code null}.
     * <p>
     * Ищется вверх по дереву от рабочего каталога — тем же приёмом, которым
     * тесты находят {@code examples/}. Причина та же: рабочий каталог задаёт
     * тот, кто запустил, и у Gradle, у IDE и у собранного дистрибутива он разный.
     */
    static Path demoGame() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null;
                directory = directory.getParent()) {
            Path candidate = directory.resolve("examples").resolve("game").resolve("pong.wdl");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void usage(PrintStream console) {
        console.println("WDGame — маленький игровой движок, играющий скриптами на wdl.");
        console.println();
        console.println("  wdgame <файл.wdl>       запустить игру");
        console.println("  wdgame                  запустить пример examples/game/pong.wdl");
        console.println("  wdgame --help           эта справка");
        console.println();
        console.println("Скрипту доступны стандартная библиотека и модуль game:");
        console.println("  import game as g");
        console.println("  world = new g.World(title: \"Игра\", width: 800, height: 480)");
        console.println("  world.spawn(\"ball\", x: 100, y: 100, w: 12, h: 12, shape: \"oval\")");
        console.println("  world.onUpdate(def (dt) { ... })");
        console.println("  world.run()");
        console.println();
        console.println("Пример игры: examples/game/pong.wdl");
    }
}
