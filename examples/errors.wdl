// Ошибки: иерархия Exception, throw, try / catch / finally.
// Подробности и обоснования — в docs/errors.md.

// --- своя ошибка ---------------------------------------------------------
// Наследование обычное: Exception лежит в области видимости с самого начала,
// рядом с println. Поле с именем text закрыло бы метод text(), поэтому — raw.

class ParseError(raw) : Exception("не число: " + raw)

class ConfigError(path, cause) : Exception("конфиг '" + path + "' не прочитан", cause)

def parsePort(raw) {
    if (raw == "") throw new ParseError(raw);
    if (raw == "0") throw new ValueError("порт вне диапазона: 0");
    return 8080;
}

def loadPort(raw, fallback) {
    try {
        return parsePort(raw);
    } catch (e is ParseError) {
        println("  [info] ", e.text(), ", беру ", fallback)
        return fallback;
    } catch (e is ValueError) {
        // Это уже не «нормально»: заворачиваем, сохраняя причину.
        throw new ConfigError("app.cfg", e);
    }
}

println("порт из пустой строки: ", loadPort("", 80))
println("порт из \"7000\": ", loadPort("7000", 80))

try {
    loadPort("0", 80)
} catch (e is ConfigError) {
    println("ошибка конфига: ", e.message)
    println("  причина: ", e.cause.text())
}

// --- ошибки движка -------------------------------------------------------
// У всего, что бросает сам движок, есть класс, и ловится он так же, как свой.

items = [10, 20]
try {
    println(items[7])
} catch (e is IndexError) {
    println("поймано ", e.kind, ": ", e.message)
}

// Три уровня грубости: свой класс, любая ошибка движка, вообще всё.
try {
    println(1 / 0)
} catch (e is IndexError) {
    println("не сюда")
} catch (e is RuntimeError) {
    println("сюда: ", e.kind)
}

// --- finally -------------------------------------------------------------
// Выполняется при любом выходе — в том числе через return и через ошибку.

def withCleanup(fail) {
    try {
        if (fail) throw new Exception("не вышло");
        return "готово";
    } catch (e) {
        return "поймал: " + e.message;
    } finally {
        println("  прибрались")
    }
}

println(withCleanup(false))
println(withCleanup(true))

// --- короткие формы ------------------------------------------------------
// try? — «мне не важно, что именно случилось»: ошибка даёт null. Это ровно тот
// случай, ради которого иначе пишут пустой catch, — только его видно в строке.

port = try? parsePort("")
println("try? на ошибке: ", port)
println("try? без ошибки: ", try? parsePort("7000"))

// try! — не подавление, а утверждение: «здесь ошибки быть не может». Если она
// всё-таки случится, это дефект скрипта, и ловить его обработчиком неправильно.
println("try! на хорошем значении: ", try! parsePort("7000"))

// --- ловля по трейту -----------------------------------------------------
// Одна ошибка бывает и HttpError, и Retriable, а родитель у класса ровно один.

trait Retriable {
    def delayMs()
}

class HttpError(code) : Exception("HTTP " + code) with Retriable {
    def delayMs() => code >= 500 ? 1000 : 200
}

try {
    throw new HttpError(503)
} catch (e is Retriable) {
    println(e.text(), ", повтор через ", e.delayMs(), " мс")
}

// --- путь по скрипту -----------------------------------------------------
// Трейс собирается из кадров вызова: имя функции и место, откуда её позвали.

def inner() => 1 / 0
def middle() => inner()

try {
    middle()
} catch (e) {
    println("где рвануло: ", e.at)
    for (frame in e.trace) println("  ", frame)
}
