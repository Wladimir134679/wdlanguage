plugins {
    id("wdl.java-conventions")
    `java-library`
}

description = "Ядро языка: лексер, парсер, AST, значения, интерпретатор. Без внешних зависимостей."

/**
 * Ядро обязано оставаться без единой внешней зависимости — именно это позволяет
 * встраивать wdl в любой проект (моды, Android, чужие приложения) без конфликтов версий.
 * Правило не на словах, а исполняемое: сборка упадёт при первой же попытке нарушить.
 */
val checkNoRuntimeDependencies = tasks.register("checkNoRuntimeDependencies") {
    group = "verification"
    description = "Падает, если у wdl-core появилась хоть одна внешняя runtime-зависимость."

    val declared = configurations.getByName("runtimeClasspath")
        .allDependencies
        .map { "${it.group}:${it.name}:${it.version}" }

    doLast {
        if (declared.isNotEmpty()) {
            throw GradleException(
                """
                wdl-core должен оставаться модулем с нулём зависимостей, но объявлены:
                    ${declared.joinToString("\n                    ")}

                Если зависимость действительно нужна — её место в wdl-stdlib, wdl-api,
                wdl-tools или wdl-cli, но не в ядре.
                """.trimIndent()
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkNoRuntimeDependencies)
}
