plugins {
    id("wdl.java-conventions")
    `java-library`
}

description = "Стандартная библиотека std: математика, File, Random. Заодно пример встраивания."

dependencies {
    // api, а не implementation: тот, кто пишет свой модуль стандартной библиотеки,
    // работает с типами из ядра (Value, Function) напрямую.
    api(project(":wdl-core"))
}
