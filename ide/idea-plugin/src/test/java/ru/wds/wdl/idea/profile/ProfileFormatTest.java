package ru.wds.wdl.idea.profile;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Чтение отчёта {@code wdl --profile-out}: образец записан настоящим запуском. */
public final class ProfileFormatTest extends BasePlatformTestCase {

    private static ProfileReport sample() throws IOException {
        try (InputStream stream = ProfileFormatTest.class.getResourceAsStream("/profile-sample.json")) {
            assertNotNull("образец отчёта не найден в ресурсах теста", stream);
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return ProfileFormat.parse(reader);
            }
        }
    }

    public void testReadsSampleReport() throws IOException {
        ProfileReport report = sample();
        assertEquals(ProfileReport.FORMAT_VERSION, report.version());
        assertEquals(21901L, report.calls());
        assertEquals(1, report.threads());
        assertTrue(report.script().endsWith("profiling.wdl"));
        assertFalse(report.isEmpty());
    }

    public void testSitesKeepHottestFirst() throws IOException {
        List<ProfileSite> sites = sample().sites();
        assertEquals("fib", sites.get(0).name());
        for (int index = 1; index < sites.size(); index++) {
            assertTrue("порядок «от горячей к холодной» нарушен на " + index,
                    sites.get(index - 1).selfNanos() >= sites.get(index).selfNanos());
        }
    }

    public void testSiteWithoutPlaceIsNotNavigable() throws IOException {
        ProfileSite builtin = sample().sites().stream()
                .filter(site -> site.name().equals("println")).findFirst().orElseThrow();
        assertEquals("native", builtin.kind());
        assertFalse(builtin.hasPlace());
        assertEquals("println", builtin.title());
        assertFalse(ProfileFiles.navigate(getProject(), builtin, Path.of(".")));
    }

    public void testTitleNamesPlaceOfDeclaration() throws IOException {
        ProfileSite function = sample().sites().get(0);
        assertEquals("fib (profiling.wdl:6)", function.title());
        ProfileSite script = sample().sites().stream()
                .filter(site -> site.kind().equals("script")).findFirst().orElseThrow();
        // Файл сам себе имя: «profiling.wdl (profiling.wdl:1)» ничего не добавляет.
        assertEquals(script.name(), script.title());
    }

    public void testTreeGrowsFromEdges() throws IOException {
        ProfileReport report = sample();
        List<Integer> roots = report.roots();
        assertEquals(1, roots.size());
        assertEquals("script", report.sites().get(roots.get(0)).kind());
        List<String> called = report.callees(roots.get(0)).stream()
                .map(edge -> report.sites().get(edge.callee()).name()).toList();
        assertTrue("корень зовёт report: " + called, called.contains("report"));
        int fib = 0;
        assertTrue("рекурсивное ребро fib → fib должно быть в графе",
                report.callees(fib).stream().anyMatch(edge -> edge.callee() == fib));
    }

    public void testForeignVersionIsRefusedWithWords() throws IOException {
        String text = "{\"version\": 7, \"sites\": [], \"edges\": []}";
        try {
            ProfileFormat.parse(new StringReader(text));
            fail("отчёт незнакомой версии принят");
        } catch (ProfileFormat.BadProfileException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("другой версии wdl"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("получена 7"));
        }
    }

    public void testGarbageIsRefusedAsFormatError() {
        try {
            ProfileFormat.parse(new StringReader("не json вовсе"));
            fail("мусор разобран как отчёт");
        } catch (IOException expected) {
            assertTrue(expected instanceof ProfileFormat.BadProfileException);
        }
    }

    public void testMissingFileIsCalledProfileNotReceived() throws IOException {
        Path absent = Files.createTempDirectory("wdl-profile-test").resolve("нет.json");
        try {
            ProfileFormat.read(absent);
            fail("отсутствующий файл прочитан");
        } catch (ProfileFormat.BadProfileException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Профиль не получен"));
        }
    }

    public void testRelativePathIsResolvedFromWorkingDirectory() throws IOException {
        Path directory = Files.createTempDirectory("wdl-profile-cwd");
        Path script = directory.resolve("main.wdl");
        Files.writeString(script, "println(\"hi\")\n", StandardCharsets.UTF_8);
        assertNotNull(ProfileFiles.locate("main.wdl", directory));
        assertNotNull(ProfileFiles.locate(script.toString(), null));
        assertNull(ProfileFiles.locate("main.wdl", directory.resolve("nested")));
        assertNull(ProfileFiles.locate("", directory));
    }
}
