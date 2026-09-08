package ru.wds.wdl.idea.profile;

import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import ru.wds.wdl.idea.WdlRunConfiguration;
import ru.wds.wdl.idea.WdlRunConfigurationType;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import javax.swing.tree.DefaultMutableTreeNode;

/** Кнопка «Профилировать» и то, что показывают по её итогам. */
public final class WdlProfileTest extends BasePlatformTestCase {

    private WdlRunConfiguration configuration() {
        var factory = ConfigurationTypeUtil.findConfigurationType(WdlRunConfigurationType.class)
                .getConfigurationFactories()[0];
        // Цель запуска здесь не важна: проверяется порядок ключей, а не то, что выполнят.
        return (WdlRunConfiguration) factory.createTemplateConfiguration(getProject());
    }

    private static ProfileReport sample() throws IOException {
        try (InputStream stream = WdlProfileTest.class.getResourceAsStream("/profile-sample.json");
             Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return ProfileFormat.parse(reader);
        }
    }

    public void testProfileRunAsksForReportBeforeScriptArguments() {
        Path launcher = Path.of("distribution", "bin", "wdl").toAbsolutePath();
        Path report = Path.of("temp", "wdl-profile.json").toAbsolutePath();
        List<String> args = configuration().createCommandLine(launcher, report)
                .getParametersList().getList();
        int flag = args.indexOf("--profile-out");
        assertTrue("флага --profile-out нет вовсе", flag >= 0);
        assertEquals(report.toString(), args.get(flag + 1));
        // Всё, что после `--`, — аргументы скрипта: флаг обязан стоять до него.
        assertTrue("--profile-out оказался в аргументах скрипта", flag < args.indexOf("--"));
    }

    public void testOrdinaryRunGetsNoProfileFlag() {
        Path launcher = Path.of("distribution", "bin", "wdl").toAbsolutePath();
        List<String> args = configuration().createCommandLine(launcher).getParametersList().getList();
        assertFalse("обычный запуск не должен ничего профилировать", args.contains("--profile-out"));
    }

    public void testRunnerAnswersOnlyForProfileButton() {
        var runner = new WdlProfileRunner();
        var configuration = configuration();
        assertTrue(runner.canRun(WdlProfileExecutor.ID, configuration));
        assertFalse(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, configuration));
    }

    public void testExecutorIsNamedInRussian() {
        var executor = new WdlProfileExecutor();
        assertEquals(WdlProfileExecutor.ID, executor.getId());
        assertEquals("Профилировать", executor.getStartActionText());
        assertTrue(executor.getStartActionText("pong").contains("pong"));
    }

    public void testFooterRepeatsConsoleLine() throws IOException {
        ProfileReport report = sample();
        String footer = ProfilePanel.footerText(report);
        assertTrue(footer, footer.startsWith("вызовов 21901 · сам "));
        assertTrue(footer, footer.contains(" · по часам "));
        // Поток один — про потоки в консоли не пишут, и здесь не должны.
        assertFalse(footer, footer.contains("потоков"));
    }

    public void testTreeStopsOnRecursion() throws IOException {
        ProfileReport report = sample();
        DefaultMutableTreeNode root = ProfilePanel.treeRoot(report);
        assertEquals(1, root.getChildCount());
        var script = (ProfilePanel.CallNode) root.getChildAt(0);
        assertEquals("script", report.sites().get(script.site).kind());

        ProfilePanel.CallNode report_ = find(script, report, "report");
        assertNotNull("корень зовёт report — его в дереве нет", report_);
        ProfilePanel.CallNode fib = find(report_, report, "fib");
        assertNotNull("до fib дерево не дошло", fib);
        assertFalse("первый вход в fib рекурсией не считается", fib.recursive);
        ProfilePanel.CallNode again = find(fib, report, "fib");
        assertNotNull("рекурсивного ребра в дереве нет", again);
        assertTrue("повтор на своём пути обязан быть помечен", again.recursive);
        assertTrue("помеченный узел дальше не разворачивается", again.isLeaf());
        assertEquals(0, again.getChildCount());
    }

    private static ProfilePanel.CallNode find(ProfilePanel.CallNode parent, ProfileReport report, String name) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            var child = (ProfilePanel.CallNode) parent.getChildAt(index);
            if (report.sites().get(child.site).name().equals(name)) {
                return child;
            }
        }
        return null;
    }

    public void testHintCountsCallsInRussian() throws IOException {
        ProfileReport report = sample();
        ProfileSite fib = report.sites().get(0);
        String text = ProfileInlays.text(fib);
        assertTrue(text, text.startsWith("21891 вызов "));
        assertTrue(text, text.endsWith(" сам"));
        assertEquals("1 вызов · 0,00 мс сам",
                ProfileInlays.text(new ProfileSite("function", "x", "a.wdl", 1, 0, 1, 0, 0, 0)));
        assertEquals("2 вызова · 0,00 мс сам",
                ProfileInlays.text(new ProfileSite("function", "x", "a.wdl", 1, 0, 2, 0, 0, 0)));
        assertEquals("11 вызовов · 0,00 мс сам",
                ProfileInlays.text(new ProfileSite("function", "x", "a.wdl", 1, 0, 11, 0, 0, 0)));
    }

    public void testHintsAreOffUntilAsked() {
        WdlProfileService service = WdlProfileService.getInstance(getProject());
        assertFalse("подсказки от вчерашнего прогона врут молча — они выключены", service.hintsEnabled());
        assertNull(service.last());
    }
}
