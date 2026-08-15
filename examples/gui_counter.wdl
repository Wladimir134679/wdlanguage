// Пример GUI приложения на WDL: Счётчик.
// Здесь же видно, зачем нужны именованные аргументы: у окна и у компоновщика
// параметров больше двух, и по числам в скобках не понять, что есть что.
import sys.gui as gui

// Поля заголовка класса называются по именам — 'new gui.Window(title: ...)'
win = new gui.Window(title: "Счётчик", width: 320, height: 180)
win.setLayout(layout: gui.flow(align: "center", hgap: 10, vgap: 10))

lbl = new gui.Label(text: "Значение: 0")
btnInc = new gui.Button(text: "+1")
btnReset = new gui.Button(text: "Сброс")

count = 0

btnInc.onClick(handler: def () {
    count += 1
    lbl.setText(text: "Значение: " + count)
})

btnReset.onClick(handler: def () {
    // title можно задать, не повторяя остальных: пропуск в середине допустим
    if (gui.confirm("Сбросить счётчик?", title: "Сброс")) {
        count = 0
        lbl.setText(text: "Значение: 0")
        gui.alert("Счётчик сброшен", title: "Уведомление")
    }
})

win.add(component: lbl)
win.add(component: btnInc)
win.add(component: btnReset)

win.show()
