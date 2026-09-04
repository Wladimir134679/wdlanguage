// Аннотации: данные, приписанные объявлению и доступные потом.
// Запуск: wdl examples/annotations.wdl
//
// Правила — в docs/annotations.md. Коротко: пишутся объектным литералом перед
// объявлением, хранятся в самом значении, читаются членом annotations и ничего
// не делают сами по себе — проверку и регистрацию пишет тот, кому они нужны.

// --- роутинг: аннотация несёт данные, декоратор регистрирует -----------------
//
// Разделение труда, ради которого всё и затевалось: что это — говорит @{...},
// что с этим сделать — говорит @[...].

ROUTES = []

def route(meta) {
    a = meta.target.annotations
    ROUTES.push({**a, handler: meta.target, name: meta.name})
}

@{path: "/users", method: "GET", auth: false}
@[route]
def listUsers(page = 1) => "страница " + page

@{path: "/users", method: "POST", auth: true}
@[route]
def createUser(body) => "создан " + body

def dispatch(method, path, user) {
    for (r in ROUTES) {
        if (r["method"] == method && r["path"] == path) {
            if (r["auth"] && user == null) return "нужна авторизация";
            return r["handler"]("1");
        }
    }
    return "нет обработчика: " + method + " " + path;
}

println(dispatch("GET", "/users", null))        // страница 1
println(dispatch("POST", "/users", null))       // нужна авторизация
println(dispatch("POST", "/users", "аня"))      // создан 1
println(dispatch("PUT", "/users", "аня"))       // нет обработчика: PUT /users

// Второй потребитель тех же данных не трогает ни одного объявления.

def openapi() {
    lines = []
    for (r in ROUTES) lines.push(r["method"] + " " + r["path"])
    return lines.join("; ");
}

println(openapi())                              // GET /users; POST /users

// --- валидация: аннотация на параметре — она же аннотация на поле ------------

class User(
    @{required: true, max: 50} name,
    @{min: 0, max: 150} age = 0,
    @{contains: "@"} email = ""
) { }

def validate(cls, instance) {
    errors = []
    for (p in cls.params) {
        a = p["annotations"]
        value = instance[p["name"]]

        if (a.get("required", false) && value == "") {
            errors.push(p["name"] + ": обязательное поле")
        }
        if (a.has("max") && value is Number && value > a["max"]) {
            errors.push(p["name"] + ": не больше " + a["max"])
        }
        if (a.has("contains") && !(a["contains"] in value)) {
            errors.push(p["name"] + ": ожидается " + a["contains"])
        }
    }
    return errors;
}

// Экземпляр создался без единой жалобы: аннотации не проверяют, проверку зовут явно.
println(validate(User, new User("", 200, "нет")))
println(validate(User, new User("Аня", 30, "anya@example.com")))     // []

// --- отчёт по тем же данным: третий потребитель, ни одной правки в классе ----

@{table: "users"}
class Row(
    @{column: "id", primary: true} id,
    @{column: "full_name", json: "fullName"} name,
    @{column: "created_at", readonly: true} created = null
) { }

def toJson(cls, instance) {
    result = {}
    for (p in cls.params) result[p["annotations"].get("json", p["name"])] = instance[p["name"]]
    return result;
}

def insertSql(cls) {
    columns = []
    for (p in cls.params) {
        a = p["annotations"]
        if (!a.get("readonly", false)) columns.push(a["column"])
    }
    return "INSERT INTO " + cls.annotations["table"] + " (" + columns.join(", ") + ")";
}

println(toJson(Row, new Row(1, "Аня Иванова")))     // {"id": 1, "fullName": "Аня Иванова", ...}
println(insertSql(Row))                             // INSERT INTO users (id, full_name)

// --- члены класса: метод и свойство по имени --------------------------------

class Account(id) {
    @{transactional: true}
    def transfer(amount) => amount

    @{computed: true}
    property label => "счёт " + id
}

println(Account.method("transfer").annotations)     // {"transactional": true}
println(Account.property("label").annotations)      // {"computed": true}
println(Account.property("label")["readonly"])      // true
println(Account.method("nope"))                     // null — такого метода нет

// --- чего аннотации не делают -----------------------------------------------

@{a: 1}
def marked() { }

// Снимок: запись в результат объявление не меняет.
marked.annotations["a"] = 99
println(marked.annotations["a"])                    // 1

// Не наследуются: родительские берутся у родителя, а склеивает их тот, кому надо.
@{level: "base", table: "rows"}
class Base(x) { }

@{level: "child", extra: true}
class Child(x) : Base(x) { }

def inherited(cls) {
    return cls.parent == null
        ? cls.annotations
        : {**inherited(cls.parent), **cls.annotations};      // ближний побеждает
}

println(Child.annotations)                          // {"level": "child", "extra": true}
println(inherited(Child))                           // + "table": "rows" от родителя

// У встроенной функции и у объявления без аннотаций — пустой объект, а не null.
println(println.annotations)                        // {}
println(Base.method("nope"))                        // null
