package ru.wds.wdl.dap;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.tools.debug.BreakpointPlaces;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Файлы скрипта так, как их видят обе стороны протокола.
 * <p>
 * Задача одна и совсем не формальная: <b>сойтись в имени файла</b>. Сессия отладки
 * сверяет точку останова с именем исходника строкой ({@code Breakpoints}), а имя
 * исходника — это то, что попало в {@code Source.name()}: путь, которым файл открыли.
 * Клиент присылает свой путь — с другим регистром диска, с прямыми косыми, с
 * {@code ..} посередине. Поэтому оба пути прогоняются через {@link #canonical(String)},
 * и делает это <b>только</b> этот класс: второе место, приводящее путь к виду,
 * однажды приведёт его иначе, и точка молча перестанет срабатывать.
 *
 * <h2>Зачем здесь разбор</h2>
 * Точка задаётся смещением, а клиент знает строку, и перевести одно в другое умеет
 * {@link BreakpointPlaces} — по дереву файла. Дерево нужно <b>до</b> запуска (точки
 * ставятся раньше) и для файлов, которые запуск может не прочитать вовсе (модуль,
 * чей {@code import} не выполнится). Поэтому файл разбирается здесь отдельно от
 * запуска — лексером и парсером, без выполнения.
 * <p>
 * Разбор кэшируется по времени изменения файла: клиент присылает
 * {@code setBreakpoints} на каждое движение точки мышью, а «файл не менялся» —
 * обычный случай.
 */
final class Sources {

    /** Разобранный файл: исходник, дерево и отметка времени, по которой видно устаревание. */
    private record Parsed(Source source, Program program, long stamp) {
    }

    private final Map<String, Parsed> cache = new ConcurrentHashMap<>();

    /**
     * Путь в том виде, в котором его увидит {@code Source.name()}: абсолютный,
     * нормализованный, с разделителями этой системы.
     * <p>
     * Нечитаемый путь возвращается как есть: соврать про имя хуже, чем не найти файл, —
     * во втором случае точка честно окажется непроверенной.
     */
    static String canonical(String path) {
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (InvalidPathException notAPath) {
            return path;
        }
    }

    /** Все места, где в этом файле можно остановиться; пусто, если файл не читается. */
    List<BreakpointPlaces.Place> all(String path) {
        Parsed parsed = parse(canonical(path));
        return parsed == null ? List.of() : BreakpointPlaces.all(parsed.program(), parsed.source());
    }

    /**
     * Куда встанут точки, поставленные на эти строки.
     * <p>
     * Порядок ответа совпадает с порядком запроса, а {@code null} на месте строки,
     * для которой места не нашлось, сохраняется: клиент сопоставляет ответ со своим
     * списком по индексу.
     */
    List<BreakpointPlaces.Place> places(String path, List<Integer> lines) {
        Parsed parsed = parse(canonical(path));
        if (parsed == null) {
            List<BreakpointPlaces.Place> missing = new ArrayList<>(lines.size());
            for (int i = 0; i < lines.size(); i++) {
                missing.add(null);
            }
            return missing;
        }
        return BreakpointPlaces.at(parsed.program(), parsed.source(), lines);
    }

    /**
     * Дерево файла или {@code null}, если файла нет, он не читается или не разобрался.
     * <p>
     * Ошибки разбора здесь не показываются никому: их уже показал языковой сервер,
     * и говорить о них второй раз из отладчика значило бы дублировать диагностику
     * в другом окне. Для отладки важно другое — что точку поставить некуда.
     */
    private Parsed parse(String canonical) {
        Path file = pathOf(canonical);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        long stamp = stampOf(file);
        Parsed known = cache.get(canonical);
        if (known != null && known.stamp() == stamp) {
            return known;
        }
        Source source;
        try {
            source = Source.ofFile(file);
        } catch (IOException unreadable) {
            return null;
        }
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        // Дерево возвращается даже с ошибками: на месте неразобранного узла стоит
        // ErrorStmt, и BreakpointPlaces его местом останова не считает сам.
        Parsed parsed = new Parsed(source, program, stamp);
        cache.put(canonical, parsed);
        return parsed;
    }

    private static Path pathOf(String canonical) {
        try {
            return Path.of(canonical);
        } catch (InvalidPathException notAPath) {
            return null;
        }
    }

    private static long stampOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException unknown) {
            // Время неизвестно — считаем файл изменившимся: лишний разбор дешевле
            // точки, уехавшей не туда.
            return System.nanoTime();
        }
    }
}
