// Пример GUI приложения на WDL: Счётчик
import sys.gui as gui

win = new gui.Window("WDL Counter App", 320, 180)
win.setLayout(gui.flow("center", 10, 10))

lbl = new gui.Label("Current count: 0")
btnInc = new gui.Button("+1")
btnReset = new gui.Button("Reset")

count = 0

btnInc.onClick(def () {
    count += 1
    lbl.setText("Current count: " + count)
})

btnReset.onClick(def () {
    if (gui.confirm("Are you sure you want to reset the counter?", "Reset")) {
        count = 0
        lbl.setText("Current count: 0")
        gui.alert("Counter reset!", "Notification")
    }
})

win.add(lbl)
win.add(btnInc)
win.add(btnReset)

win.show()
