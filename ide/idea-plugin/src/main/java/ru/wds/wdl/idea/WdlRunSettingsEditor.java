package ru.wds.wdl.idea;

import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;

final class WdlRunSettingsEditor extends SettingsEditor<WdlRunConfiguration> {
    private final TextFieldWithBrowseButton target = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton directory = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton interpreter = new TextFieldWithBrowseButton();
    private final JTextField options = new JTextField();
    private final JTextField arguments = new JTextField();
    private final JPanel panel;

    WdlRunSettingsEditor() {
        target.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor());
        directory.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFolderDescriptor());
        interpreter.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileDescriptor());
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Файл / проект (main.wdl):", target)
                .addLabeledComponent("Рабочий каталог (пусто = проект IDEA):", directory)
                .addLabeledComponent("Параметры wdl:", options)
                .addLabeledComponent("Аргументы скрипта:", arguments)
                .addLabeledComponent("bin/wdl или bin/wdl.bat (пусто = автоматически):", interpreter)
                .getPanel();
    }
    @Override protected void resetEditorFrom(@NotNull WdlRunConfiguration c) {
        target.setText(c.target); directory.setText(c.workingDirectory);
        interpreter.setText(c.interpreter); options.setText(c.interpreterOptions); arguments.setText(c.scriptArguments);
    }
    @Override protected void applyEditorTo(@NotNull WdlRunConfiguration c) {
        c.target = target.getText(); c.workingDirectory = directory.getText();
        c.interpreter = interpreter.getText(); c.interpreterOptions = options.getText(); c.scriptArguments = arguments.getText();
    }
    @Override protected @NotNull JComponent createEditor() { return panel; }
}
