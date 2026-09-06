plugins {
    id("wdl.java-conventions")
    `java-library`
    application
}

description = "Языковой сервер LSP: перевод ответов wdl-tools в JSON-RPC."

dependencies {
    // Смысл живёт здесь; в этом модуле только перевод ответов в протокол.
    implementation(project(":wdl-tools"))
    // Каталог имён снимается с той же конфигурации, которую собирает `wdl`:
    // редактор обязан знать ровно то, что получит скрипт.
    implementation(project(":wdl-stdlib"))
    implementation(libs.lsp4j)
}

application {
    mainClass.set("ru.wds.wdl.lsp.Main")
    applicationName = "wdl-lsp"
    // Сообщения протокола — UTF-8 по спецификации, и логи в stderr должны читаться
    // тем же. Из IDE сервер запускают мимо стартовых скриптов, поэтому кодировку
    // задаёт и сам Main.
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}
