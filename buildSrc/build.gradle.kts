// buildSrc собирает convention-плагины, общие для всех модулей.
// Сами плагины лежат в src/main/kotlin/*.gradle.kts и подключаются по id имени файла.
plugins {
    `kotlin-dsl`
}
