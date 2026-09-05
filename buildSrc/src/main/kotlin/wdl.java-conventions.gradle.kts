import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

/**
 * Общие настройки для всех Java-модулей wdl.
 * Подключается в модуле как: plugins { id("wdl.java-conventions") }
 */
plugins {
    `java-library`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion.toInt()))
    }
    withSourcesJar()
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all",
            "-Xlint:-serial",       // свои исключения сериализовать не планируем
            "-parameters",          // имена параметров в рантайме — пригодятся мосту в Java
        )
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    defaultCharacterEncoding = "UTF-8"
    // Корпус примеров лежит в корне репозитория, а тест выполняется из каталога модуля.
    // Относительным путём это связывать нельзя: он поедет от того, кто запустил задачу.
    systemProperty("wdl.examples", rootProject.file("examples").absolutePath)
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
    }
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        docEncoding = "UTF-8"
        charSet = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}
