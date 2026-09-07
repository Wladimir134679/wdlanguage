plugins {
    id("wdl.publish-conventions")
    `java-library`
}

description = "Инструменты разработчика: линтер, форматтер, дамп AST. Позже — LSP."

dependencies {
    api(project(":wdl-core"))
}
