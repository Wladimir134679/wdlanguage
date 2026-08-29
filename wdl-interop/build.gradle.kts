plugins {
    id("wdl.java-conventions")
    `java-library`
}

description = "Мост в Java через рефлексию: чужой класс и готовый объект как класс и объект языка."

dependencies {
    // api, а не implementation: тот, кто открывает скрипту свои классы, работает
    // с типами ядра (Value, ClassValue, Span) напрямую.
    api(project(":wdl-core"))
}
