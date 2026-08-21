// Свойства: имя, за которым стоит код, а не ячейка.
// Читается и пишется тем же обращением, что поле.

// --- вычисляемое свойство ---------------------------------------------------

class Rect(w, h) {
    // Короткая форма: только чтение.
    property area => this.w * this.h

    // Блок: чтение и запись под одним именем.
    property side {
        def get() => this.w

        def set(value) {
            this.w = value
            this.h = value
        }
    }

    def text() => this.w + "x" + this.h
}

r = new Rect(10, 20)
println("площадь: ", r.area)          // 200

r.w = 15
println("после правки: ", r.area)     // 300 — свойство считается заново

r.side = 4
println("сторона: ", r.text(), ", площадь ", r.area)   // 4x4, 16

// Обращение одно на все формы записи: точка и вычисленный ключ равнозначны.
key = "area"
println("по ключу: ", r[key])

// Свойства нет среди пар объекта — как нет и метода.
names = ""
for (k in r) names += k + " "
println("поля: ", names)              // w h

// --- только для чтения ------------------------------------------------------

try {
    r.area = 100
} catch (e) {
    println("отказ: ", e.message)
}

// --- скрытое поле -----------------------------------------------------------

// 'property x = 0' заводит место для значения, которого нет среди полей объекта.
// Внутри аксессоров оно видно под именем field.

class Box() {
    property size = 0 {
        def get() => field

        def set(value) {
            // Инвариант держится в одном месте, а не в каждом, кто пишет поле.
            field = value < 0 ? 0 : value
        }
    }
}

b = new Box()
b.size = 5
println("размер: ", b.size)           // 5
b.size = -3
println("после -3: ", b.size)         // 0 — сеттер выправил
println("объект: ", b)                // Box{} — скрытое поле не видно

// --- скрытие цепочки --------------------------------------------------------

// То, ради чего свойства и заводят: длинный путь и проверки прячутся за именем.

class Plan(gigabytes)
class Limits(storage)
class Settings(limits)

class Application(config) {
    property storageLimit {
        def get() => this.config.limits.storage.gigabytes

        def set(value) {
            this.config.limits.storage.gigabytes = value < 0 ? 0 : value
        }
    }
}

app = new Application(new Settings(new Limits(new Plan(100))))
println("лимит: ", app.storageLimit)
app.storageLimit = 500
println("новый лимит: ", app.storageLimit)

// --- требования трейта ------------------------------------------------------

// Требование выражено через возможность, а не через способ хранения: его закрывает
// и обычное поле, и вычисляемое свойство. Поэтому одно можно заменить другим,
// не сломав контракт.

trait Sized {
    property size {
        def get()
    }

    def describe() => "размер " + this.size
}

class Fixed(size) with Sized              // поле закрывает требование
class Computed(w) with Sized {            // свойство закрывает его же
    property size => this.w * 3
}

println(new Fixed(7).describe())
println(new Computed(4).describe())
