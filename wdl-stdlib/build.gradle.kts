plugins {
    id("wdl.java-conventions")
    `java-library`
}

description = "Библиотека std (математика, File, Random) и встроенные модули sys.io, sys.json, sys.net.http. Заодно пример встраивания."

dependencies {
    // api, а не implementation: тот, кто пишет свой модуль стандартной библиотеки,
    // работает с типами из ядра (Value, Function) напрямую.
    api(project(":wdl-core"))

    // Мост: библиотеки над чужими типами (sys.time) и над своими обычными
    // Java-классами (Random) описываются схемой, а не построителем лямбд.
    api(project(":wdl-interop"))
}
