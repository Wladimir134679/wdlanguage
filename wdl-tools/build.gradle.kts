plugins {
    id("wdl.java-conventions")
    `java-library`
}

description = "Инструменты разработчика: линтер, форматтер, дамп AST. Позже — LSP."

dependencies {
    api(project(":wdl-core"))
}
