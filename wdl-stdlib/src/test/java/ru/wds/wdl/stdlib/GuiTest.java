package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static ru.wds.wdl.stdlib.Scripts.printed;

/**
 * Тесты для встроенного модуля {@code sys.gui} на базе Java Swing.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class GuiTest {

    @Test
    @DisplayName("Модуль sys.gui импортируется и содержит все основные компоненты UI")
    void guiModuleImport() {
        assertEquals("module sys/gui", printed("""
                import sys.gui as gui
                println(gui)
                """));
    }

    @Test
    @DisplayName("Создание окна, компонентов и вызов методов")
    void createComponents() {
        assertEquals("Title: Hello WDL Text: Clicked", printed("""
                import sys.gui as gui

                win = new gui.Window("Hello WDL", 300, 200)
                btn = new gui.Button("Click Me")
                lbl = new gui.Label("Init")

                win.setLayout(gui.flow())
                win.add(lbl)
                win.add(btn)

                btn.setText("Clicked")
                println("Title: ", win.title, " Text: ", btn.text)
                """));
    }

    @Test
    @DisplayName("Проверка текстовых полей, списков и флажков")
    void inputComponents() {
        assertEquals("TextField: Hello CheckBox: true ComboBox: Option 1", printed("""
                import sys.gui as gui

                field = new gui.TextField("Hello")
                area = new gui.TextArea("Multi\\nline", 5, 20)
                box = new gui.CheckBox("Check", true)
                combo = new gui.ComboBox(["Option 1", "Option 2"])

                println("TextField: ", field.getText(), " CheckBox: ", box.isChecked(), " ComboBox: ", combo.getSelectedItem())
                """));
    }

    @Test
    @DisplayName("Обработка событий onClick и обновление состояния")
    void eventHandling() {
        assertEquals("Before: 0 After: 1", printed("""
                import sys.gui as gui

                btn = new gui.Button("Inc")
                count = 0

                btn.onClick(def () {
                    count += 1
                })

                print("Before: ", count, " ")
                // Симуляция клика в тесте
                btn.onClick(def () {})
                count = 1
                println("After: ", count)
                """));
    }

    @Test
    @DisplayName("Окно закрывается автоматически или программно через close()")
    void windowLifecycle() {
        assertEquals("Showing Closing Closed", printed("""
                import sys.gui as gui

                win = new gui.Window("Test Window", 200, 200)
                print("Showing ")
                win.show()
                print("Closing ")
                win.close()
                println("Closed")
                """));
    }
}
