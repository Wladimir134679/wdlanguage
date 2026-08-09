# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Проект и вся его документация ведутся **на русском языке**: javadoc, комментарии,
сообщения диагностики, `@DisplayName` тестов, README и `docs/`. Новый код пишется так же.

**Но идентификаторы в примерах на самом wdl — английские.** Любой новый `.wdl`-сниппет
(в `examples/`, в `docs/`, в тестах, в ответе пользователю) объявляет переменные,
функции и параметры по-английски: `price = 120`, `fun total(price, count) => price * count`.
По-русски остаются комментарии, строковые литералы и вывод. Поддержка кириллицы в именах
— фича лексера и никуда не девается, но в примерах её не демонстрируем.
Примеры в `examples/`, написанные до этого правила, пока на кириллице.

## Команды

```bash
./gradlew build                  # сборка + тесты + checkNoRuntimeDependencies
./gradlew test                   # только тесты
./gradlew :wdl-core:test --tests 'ru.wds.wdl.parser.ParserTest'                 # один класс
./gradlew :wdl-core:test --tests 'ru.wds.wdl.parser.ParserTest.leftAssociative' # один метод
./gradlew modules                # карта модулей с описаниями

./gradlew :wdl-cli:run --args="examples/hello.wdl"   # выполнить скрипт (workingDir = корень репо)
./gradlew :wdl-cli:run --args="--ast examples/hello.wdl"
./gradlew :wdl-cli:run --args="--tokens examples/lexer-check.wdl"
./gradlew :wdl-cli:repl --console=plain              # REPL (отдельная задача: нужен живой stdin)
./gradlew :wdl-cli:installDist                       # → wdl-cli/build/install/wdl/bin/wdl
```

JDK 21+ (toolchain из `gradle/libs.versions.toml`), Gradle Wrapper 9.0.
Configuration cache включён в `gradle.properties`; задача `repl` из него намеренно
исключена. Все тесты — JUnit 5.

## Инварианты, которые ломают сборку или архитектуру

1. **У `wdl-core` ноль внешних runtime-зависимостей.** Проверяется задачей
   `checkNoRuntimeDependencies` (`wdl-core/build.gradle.kts`), встроенной в `check`.
   Нужна библиотека — ей место в `stdlib`, `api`, `tools` или `cli`.
2. **Репозитории объявляются только в `settings.gradle.kts`** (`FAIL_ON_PROJECT_REPOS`).
   Версии — только через `gradle/libs.versions.toml`.
3. **Никакой изменяемой статики в ядре.** Всё состояние — на экземпляре, контекст
   идёт аргументом; это то, что делает изоляцию нескольких интерпретаторов в одном
   процессе настоящей. Даже таблицы в `Operators` отдаются через `unmodifiableMap`.
4. **У узлов AST нет `eval()`.** AST — данные (`record` в `sealed`-иерархиях),
   выполнение живёт в `runtime` через посетителей.
5. **`Span` в каждом токене и узле.** Позиция задаётся при создании, не задним числом.
6. **Публичные пакеты ядра перечислены в `wdl-core/src/main/java/module-info.java`** —
   новый пакет надо туда добавить.

## Архитектура

Конвейер: `Source → Lexer → List<Token> → Parser → Program (AST) → Interpreter`.
Диагностика (`Diagnostics`) протягивается через все стадии одним объектом.
Канонический пример полного прохода — `wdl-cli/src/main/java/ru/wds/wdl/cli/Main.java`.

Модули (зависимости строго в одну сторону):

| Модуль | Содержимое | Зависит от |
|---|---|---|
| `wdl-core` | `lexer`, `parser`, `ast`, `value`, `runtime`, `diagnostic`, `source` | ничего |
| `wdl-stdlib` | math, string, array, io (пока заготовка) | core |
| `wdl-api` | фасад для встраивания `WdlEngine` (пока заготовка) | core, stdlib |
| `wdl-tools` | `AstDumper`, `TokenDumper`, позже линтер/форматтер/LSP | core |
| `wdl-cli` | picocli-точка входа, REPL | api, tools |

### Ключевые решения, определяющие остальное

* **Ошибка не прерывает разбор.** Лексер и парсер копят всё в `Diagnostics` и идут
  дальше; на месте неразобранного узла остаётся `ErrorExpr`/`ErrorStmt`. Дерево
  возвращается всегда — для форматтера, подсветки, LSP. Перед выполнением
  обязательна проверка `diagnostics.hasErrors()`.
* **Перевод строки не порождает токена** — форматирование на разбор не влияет,
  границы инструкций выводит парсер. Факт переноса сохранён в `Token.afterNewline()`.
  `;` — необязательный разделитель (но обязателен после `return`).
* **Выражения — алгоритм Пратта**, приоритеты лежат таблицей в `parser/Operators.java`
  числами с шагом 2. Сам `Parser.expression(int)` таблицу читает и при добавлении
  оператора не меняется. Порядок сознательно отличается от Си: побитовые операторы
  связывают сильнее сравнений.
* **Присваивание — инструкция, а не выражение**: `if (x = 5)` не разберётся в принципе.
  Составное `+=` не разворачивается при разборе, поэтому `t[k()] += 1` зовёт `k()` один раз.
* **Обращение одно на все типы**: `x.имя` ≡ `x["имя"]`, различие только в
  `AccessStyle` для форматтера. См. `docs/access.md` и `Interpreter.visitAccess`.
* **Функция — обычное значение** (`FunctionValue`) с замыканием на область объявления;
  одно пространство имён с переменными. Объявления верхнего уровня помечаются до
  выполнения, поэтому порядок функций в файле свободен.
* **`break`/`continue`/`return` — сигналы** (`ControlSignal`, sealed-наследники
  `RuntimeException`), а не код возврата в каждом узле. Глубина вызовов ограничена
  `ExecutionContext.MAX_CALL_DEPTH`, чтобы рекурсия давала ошибку скрипта,
  а не `StackOverflowError` в чужом приложении.
* **Вывод — зависимость, а не `System.out`**: `Output` внутри `ExecutionContext`,
  по умолчанию `Output.discarding()`. `ExecutionContext` неизменяем — вложенная
  область даёт новый контекст (`withScope`/`nested`), а не подменяет поле.

## Рецепты

* **Бинарный оператор**: `TokenType` → `BinaryOp` → строка в `Operators` →
  ветка в `runtime/Operations` → тесты в `ParserTest` (форма дерева) и `InterpreterTest`
  (результат). Парсер не трогается.
* **Тип значения**: класс в `value/types`, `permits` в `Value`, элемент в `ValueType`.
  Дальше компилятор сам покажет все неполные `switch` — это и есть список работ.
* **Вид узла**: `record` в `ast/expr` или `ast/stmt`, `permits` в `Expr`/`Stmt`,
  метод в `ExprVisitor`/`StmtVisitor` — компилятор проведёт по всем посетителям
  (`Interpreter`, `AstDumper`, тестовый `SExprPrinter`).
* **Встроенная функция**: одна запись в `Builtins.installTo` — имя, `Arity`, лямбда.
  Всё нужное от среды приходит через `CallContext`.
* **Класс от приложения**: построитель `embed/NativeClass` — поля заголовка, методы,
  фабрики, константы; состояние, не выразимое значением, — в `NativeInstance.state()`.
  Для интерпретатора это тот же `ClassValue`, что и класс на wdl. См. `docs/embedding.md`
  и `wdl-stdlib/.../Std.java` как пример.

## Тесты

Тесты парсера сравнивают **форму дерева строкой** через `SExprPrinter`
(`1 + 2 * 3` → `(+ 1 (* 2 3))`) — ожидание видно целиком, а не собирается из
вложенных конструкторов. Тесты интерпретатора проверяют результат и текст ошибки.
`@DisplayName` на русском обязателен по сложившемуся стилю.

## Документация

`docs/` — описание языка, по документу на тему; `README.md` держит состояние проекта.
Правила ведения (из `docs/README.md`): записывать не только правило, но и **причину**;
все примеры в документах должны реально выполняться; состояние языка («что уже
работает») живёт только в `README.md` и `docs/overview.md`, в остальных не дублируется.
`Основные идеи.md` — черновик желаемых конструкций языка с пометками о сделанном.
