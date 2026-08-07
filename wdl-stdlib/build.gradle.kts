plugins {
    id("wdl.java-conventions")
    `java-library`
}

description = "Стандартная библиотека языка: math, string, array, io и прочие модули."

dependencies {
    // api, а не implementation: тот, кто пишет свой модуль стандартной библиотеки,
    // работает с типами из ядра (Value, Function) напрямую.
    api(project(":wdl-core"))
}
