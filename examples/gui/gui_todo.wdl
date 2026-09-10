// Пример GUI приложения на WDL: список задач.
// Имена аргументов особенно полезны там, где рядом стоят числа одного типа:
// 'new gui.TextArea(rows: 14, cols: 35)' против 'new gui.TextArea("", 14, 35)'.
import sys.gui as gui

win = new gui.Window(title: "Список задач", width: 450, height: 380)
win.setLayout(layout: gui.border(hgap: 10, vgap: 10))

topPanel = gui.hbox()
inputField = new gui.TextField(text: "")
addButton = new gui.Button(text: "Добавить")

topPanel.add(component: inputField)
topPanel.add(component: addButton)

// text пропущен и берёт своё значение по умолчанию — пустую строку
taskArea = new gui.TextArea(rows: 14, cols: 35)

def addTask() {
    text = inputField.text
    if (text != "") {
        taskArea.append(text: "• " + text + "\n")
        inputField.text = ""
    }
}

addButton.onClick(handler: def () => addTask())
inputField.onEnter(handler: def () => addTask())

// У 'add' второй параметр необязательный: с ним — своя сторона света, без него —
// место по умолчанию от компоновщика
win.add(component: topPanel, constraint: "North")
win.add(component: taskArea, constraint: "Center")

win.show()
