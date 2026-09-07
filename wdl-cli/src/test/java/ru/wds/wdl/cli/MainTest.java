package ru.wds.wdl.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir Path temp;

    private record Result(int status, String out, String err) { }

    private Result run(String... args) {
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (PrintStream stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setOut(stdout);
            System.setErr(stderr);
            int status = new CommandLine(new Main()).execute(args);
            return new Result(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
    }

    @Test void fileWithSpacesAndArguments() throws Exception {
        Path file = temp.resolve("скрипт с пробелами.wdl");
        Files.writeString(file, "println(args[0]); println(args[1]); println(args[2]);");
        Result result = run(file.toString(), "--", "два слова", "--metrics", "");
        assertEquals(0, result.status(), result.err());
        assertEquals("два слова\n--metrics\n\n", result.out().replace("\r\n", "\n"));
    }

    @Test void directoryUsesMainAndKeepsRelativeImports() throws Exception {
        Files.writeString(temp.resolve("main.wdl"), "import helper as helper; println(helper.answer); println(len(args));");
        Files.writeString(temp.resolve("helper.wdl"), "answer = 42;");
        Result result = run(temp.toString());
        assertEquals(0, result.status(), result.err());
        assertEquals("42\n0\n", result.out().replace("\r\n", "\n"));
    }

    @Test void directoryWithoutMainIsUsageError() {
        assertEquals(2, run(temp.toString()).status());
    }

    @Test void projectRootOverridesEntryDirectoryForNestedImports() throws Exception {
        Path scripts = Files.createDirectories(temp.resolve("scripts"));
        Path lib = Files.createDirectories(temp.resolve("lib"));
        Path entry = scripts.resolve("main.wdl");
        Files.writeString(entry, "import lib.outer; println(answer);");
        Files.writeString(lib.resolve("outer.wdl"), "import helper; answer = value;");
        Files.writeString(temp.resolve("helper.wdl"), "value = 42;");
        Files.writeString(lib.resolve("helper.wdl"), "value = 99;");
        Result result = run("--project-root", temp.toString(), entry.toString());
        assertEquals(0, result.status(), result.err());
        assertEquals("42", result.out().trim());
        assertEquals(1, run(entry.toString()).status());
        assertEquals(2, run("--project-root", entry.toString(), entry.toString()).status());
    }

    @Test void scriptErrorRetainsNonzeroExitCode() throws Exception {
        Path file = temp.resolve("broken.wdl");
        Files.writeString(file, "println(missingName);");
        assertEquals(1, run(file.toString()).status());
    }

    @Test void unknownInterpreterOptionIsNotSilentlyPassedToScript() throws Exception {
        Path file = temp.resolve("main.wdl");
        Files.writeString(file, "println(args);");
        assertEquals(2, run("--typo", file.toString()).status());
    }
}
