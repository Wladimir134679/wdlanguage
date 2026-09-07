rootProject.name = "wdlanguage"

// Репозитории объявляются ТОЛЬКО здесь.
// FAIL_ON_PROJECT_REPOS запрещает модулям заводить свои — иначе однажды
// в wdl-core просочится зависимость, а это ядро должно остаться чистым.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    "wdl-core",
    "wdl-bridge",
    "wdl-stdlib",
    "wdl-api",
    "wdl-cli",
    "wdl-tools",
    "wdl-lsp",
    "wdl-game",
)
