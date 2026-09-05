package ru.wds.wdl.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.Corpus;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Инварианты интервалов на всём, что есть: примеры языка и нарочно недописанный текст.
 * <p>
 * Второй корпус важнее первого. Правильный файл разбирается по правилам грамматики,
 * и интервалы в нём получаются верными почти сами собой; ломаются они там, где парсер
 * восстанавливается после ошибки, — то есть в том состоянии, в каком редактор видит
 * файл большую часть времени.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class AstInvariantTest {

    @Test
    @DisplayName("примеры языка: интервалы вложены и не пересекаются")
    void examples() {
        Corpus.examples().forEach(AstInvariantTest::checkFile);
    }

    @Test
    @DisplayName("недописанный текст: интервалы вложены и не пересекаются")
    void malformed() {
        Corpus.malformed().forEach(AstInvariantTest::checkFile);
    }

    @Test
    @DisplayName("пустой файл даёт пустое дерево без нарушений")
    void empty() {
        check(Source.ofString(""));
        check(Source.ofString("\n\n"));
        check(Source.ofString("// только комментарий"));
    }

    private static void checkFile(Path file) {
        check(Corpus.sourceOf(file));
    }

    private static void check(Source source) {
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        AstInvariant.check(program, source);
    }
}
