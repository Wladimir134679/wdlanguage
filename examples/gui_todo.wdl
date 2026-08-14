// Пример GUI приложения на WDL: Список задач (Todo App)
import sys.gui as gui

win = new gui.Window("WDL Todo App", 450, 380)
win.setLayout(gui.border(10, 10))

topPanel = gui.hbox()
inputField = new gui.TextField("")
addButton = new gui.Button("Add Task")

topPanel.add(inputField)
topPanel.add(addButton)

taskArea = new gui.TextArea("", 14, 35)

def addTask() {
    text = inputField.getText()
    if (text != "") {
        taskArea.append("• " + text + "\n")
        inputField.setText("")
    }
}

addButton.onClick(def () => addTask())
inputField.onEnter(def () => addTask())

win.add(topPanel, "North")
win.add(taskArea, "Center")

win.show()
