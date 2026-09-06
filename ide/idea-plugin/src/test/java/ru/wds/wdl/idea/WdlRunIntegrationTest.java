package ru.wds.wdl.idea;

import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jdom.Element;

import java.nio.file.Path;

public final class WdlRunIntegrationTest extends BasePlatformTestCase {
    public void testRunPsiStillRequestsLspHighlighting() {
        PsiFile file = myFixture.configureByText(WdlFileType.INSTANCE, "const answer = 42\nprintln(\"hello\")\n");
        var descriptor = new WdlLspServerDescriptor(getProject(), Path.of("wdl-lsp"));
        var support = (com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport)
                descriptor.getLspCustomization().getSemanticTokensCustomizer();
        // Regression: introducing a ParserDefinition changes TEXT to wdl; the platform's
        // default then silently stops semanticTokens requests even with a healthy server.
        assertFalse(new com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport()
                .shouldAskServerForSemanticTokens(file));
        assertTrue(support.shouldAskServerForSemanticTokens(file));
        assertNotNull(support.getTextAttributesKey("keyword", java.util.List.of()));
        assertNotNull(support.getTextAttributesKey("string", java.util.List.of()));
        assertNotNull(support.getTextAttributesKey("number", java.util.List.of()));
        assertNotNull(new WdlRunLineMarkerContributor().getInfo(PsiTreeUtil.getDeepestFirst(file)));
    }

    public void testWdlFileHasRunMarker() {
        PsiFile file = myFixture.configureByText(WdlFileType.INSTANCE, "println(args)\n");
        assertSame(WdlLanguage.INSTANCE, file.getLanguage());
        assertNotNull(new WdlRunLineMarkerContributor().getInfo(PsiTreeUtil.getDeepestFirst(file)));
    }

    public void testRunConfigurationPersistsParameters() {
        var factory = ConfigurationTypeUtil.findConfigurationType(WdlRunConfigurationType.class)
                .getConfigurationFactories()[0];
        var original = (WdlRunConfiguration) factory.createTemplateConfiguration(getProject());
        original.target = "some dir/main.wdl";
        original.workingDirectory = "work";
        original.interpreterOptions = "--metrics";
        original.scriptArguments = "\"two words\" --flag";
        original.interpreter = "some dir/bin/wdl";
        Element state = new Element("configuration");
        original.writeExternal(state);
        var restored = (WdlRunConfiguration) factory.createTemplateConfiguration(getProject());
        restored.readExternal(state);
        assertEquals(original.target, restored.target);
        assertEquals(original.workingDirectory, restored.workingDirectory);
        assertEquals(original.interpreterOptions, restored.interpreterOptions);
        assertEquals(original.scriptArguments, restored.scriptArguments);
        assertEquals(original.interpreter, restored.interpreter);
    }

    public void testDistributionClasspathDoesNotUseShell() {
        Path launcher = Path.of("distribution with spaces", "bin", "wdl").toAbsolutePath();
        var command = WdlCommandLine.create(launcher, "ru.wds.wdl.cli.Main");
        assertEquals(launcher.getParent().getParent().resolve("lib") + java.io.File.separator + "*",
                command.getParametersList().get(2));
        assertEquals("ru.wds.wdl.cli.Main", command.getParametersList().get(3));
    }
}
