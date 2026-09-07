plugins {
    id("wdl.publish-conventions")
    `java-library`
}

description = "Встраивание: свои классы, трейты и модули для скрипта плюс мост в Java через рефлексию."

dependencies {
    // api, а не implementation: тот, кто открывает скрипту свои классы, работает
    // с типами ядра (Value, ClassValue, Span) напрямую.
    api(project(":wdl-core"))
}
