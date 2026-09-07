package ru.wds.wdl.game;

import ru.wds.wdl.api.WdlException;
import ru.wds.wdl.game.engine.Engine;
import ru.wds.wdl.game.pong.Match;
import ru.wds.wdl.game.pong.Pong;
import ru.wds.wdl.runtime.Output;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Запуск игры: {@code wdgame}.
 * <p>
 * Приложение здесь — <b>игра</b>, а не язык. Она собирает движок, читает свои
 * встроенные скрипты и играет; wdl внутри неё занят тем же, чем был бы занят
 * в чужой игре: правилами, которые меняются чаще, чем пересобирается код.
 * Аргументов у запуска нет, и это часть замысла — скрипт здесь не «то, что
 * запускают», а часть самой игры.
 *
 * <pre>{@code
 * ./gradlew :wdl-game:run
 * ./gradlew :wdl-game:installDist   # → wdl-game/build/install/wdgame/bin/wdgame
 * }</pre>
 *
 * Управление: <b>W/S</b> или стрелки — ракетка, <b>ПРОБЕЛ</b> — подача,
 * <b>R</b> — новая партия, <b>ESC</b> — выход.
 */
public final class GameLauncher {

    /** Ошибка во встроенном скрипте. */
    private static final int EXIT_SCRIPT_ERROR = 1;
    /** Играть негде: нет графической среды или запуск позвали не так. */
    private static final int EXIT_ENVIRONMENT_ERROR = 2;

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

    static int run(String[] arguments, PrintStream console) {
        if (arguments.length > 0) {
            boolean help = isHelp(arguments[0]);
            usage(console);
            return help ? 0 : EXIT_ENVIRONMENT_ERROR;
        }

        Engine engine = Pong.arena();
        // Скрипты закрываются вместе с игрой: за ними стоит запуск wdl со своими
        // модулями, и держать его дольше окна незачем.
        try (PongScripts scripts = PongScripts.embedded(Output.standard())) {
            Pong pong = new Pong(engine, scripts);
            console.println("Пинг-понг — WDGame. W/S или стрелки — ракетка, "
                    + "ПРОБЕЛ — подача, R — заново, ESC — выход.");
            engine.run(pong);
            Match match = pong.match();
            console.println("Счёт: " + match.player() + " : " + match.rival()
                    + ", кадров сыграно: " + engine.frames());
            return 0;
        } catch (WdlException failed) {
            // Сообщение уже собрано движком — с местом во встроенном скрипте
            // и путём по вызовам.
            System.err.println(failed.getMessage());
            return EXIT_SCRIPT_ERROR;
        } catch (IllegalStateException impossible) {
            // Сюда приходит «графической среды нет» и «встроенный скрипт не
            // объявил хук»: и то и другое — про среду запуска, а не про игру.
            System.err.println(impossible.getMessage());
            return EXIT_ENVIRONMENT_ERROR;
        } finally {
            engine.close();
        }
    }

    private static boolean isHelp(String argument) {
        return "-h".equals(argument) || "--help".equals(argument);
    }

    private static void usage(PrintStream console) {
        console.println("WDGame — маленький игровой движок; пинг-понг на нём "
                + "играется по встроенным скриптам на wdl.");
        console.println();
        console.println("  wdgame            играть");
        console.println("  wdgame --help     эта справка");
        console.println();
        console.println("Управление: W/S или стрелки — ракетка, ПРОБЕЛ — подача,");
        console.println("            R — новая партия, ESC — выход.");
        console.println();
        console.println("Правила лежат скриптами внутри jar и меняются без пересборки игры:");
        console.println("  pong.wdl    какие функции игра спрашивает");
        console.println("  rules.wdl   подача, отскок от ракетки, конец партии");
        console.println("  rival.wdl   соперник");
        console.println("  hud.wdl     счёт и надписи поверх кадра");
        console.println("Исходники: wdl-game/src/main/resources/ru/wds/wdl/game/pong/");
    }
}
