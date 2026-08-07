package ru.wds.wdl.cli;

import java.io.PrintStream;

/**
 * Точка входа консольного интерпретатора.
 * <p>
 * Разбор аргументов сделан вручную и намеренно примитивно: внешних библиотек
 * в проекте нет. Если аргументов станет много — сюда придёт нормальный парсер команд.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        PrintStream out = System.out;

        if (args.length == 0) {
            printUsage(out);
            return;
        }

        switch (args[0]) {
            case "--version", "-v" -> out.println("wdl " + version());
            case "--help", "-h" -> printUsage(out);
            case "--repl" -> out.println("REPL ещё не реализован.");
            default -> {
                if (args[0].startsWith("-")) {
                    System.err.println("Неизвестный ключ: " + args[0]);
                    printUsage(System.err);
                    System.exit(2);
                }
                out.println("Запуск скриптов ещё не реализован: " + args[0]);
            }
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("""
                wdl — встраиваемый скриптовый язык для JVM

                Использование:
                  wdl <файл.wdl>     запустить скрипт
                  wdl --repl         интерактивный режим
                  wdl --version      версия
                  wdl --help         эта справка""");
    }

    private static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
