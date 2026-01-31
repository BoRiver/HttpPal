plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "com.httppal"
version = "1.0.0"

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure Java toolchain for JDK 21 (Recommended for 2025.2)
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21)) // *** Updated to 21 ***
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2025.1")

        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.fasterxml.jackson.core:jackson-core:2.19.0")
    /*implementation("com.fasterxml.jackson.core:jackson-databind:2.19.0")*/  
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    implementation("io.swagger.parser.v3:swagger-parser:2.1.37")

    // GraphQL support
    implementation("com.graphql-java:graphql-java:21.5")
    // Extended scalars (可选，提供额外的标量类型如 DateTime、JSON 等)
    // implementation("com.graphql-java:graphql-java-extended-scalars:22.0")

    // Mock data generation for HTTP requests
    implementation("net.datafaker:datafaker:2.5.1")
    implementation("com.github.curious-odd-man:rgxgen:2.0") // Regex pattern generation

    // Testing dependencies (optional, for future unit tests)
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.kotest:kotest-property:5.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.3")
}

tasks {
    withType<JavaCompile> {
        // *** Updated to match toolchain and Kotlin target ***
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) // 设置 JVM 目标版本
            freeCompilerArgs.add("-Xjsr305=strict") // 添加单个编译器参数
            // 如果要添加多个参数，可以使用 addAll:
            // freeCompilerArgs.addAll(listOf("-Xjsr305=strict", "-another-flag"))
        }
    }

    test {
        useJUnitPlatform()
        jvmArgs = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    }

    // *** Removed patchPluginXml, signPlugin, publishPlugin tasks from here ***
    // *** Their configuration is now within the intellijPlatform block below ***

    // Configure build cache - Default behavior is usually fine
    // buildPlugin {
    //     archiveClassifier.set("") // Often not needed in 2.x
    // }
}

kotlin {
    // jvmToolchain(21) // Alternative way to set toolchain, consistent with java {} block above
    compilerOptions {
        // *** Updated to match toolchain and Java compile target ***
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// *** New location for plugin-specific configurations in 2.x plugin ***
intellijPlatform {
    pluginConfiguration {

        ideaVersion {
            // *** Updated sinceBuild ***
            sinceBuild = "251" // For 2025.1+
            untilBuild = null // Allow compatibility with future versions
        }

        changeNotes = """
            Initial release.
        """.trimIndent()
    }

    // Signing configuration (moved from tasks.signPlugin)
    signing {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    // Publishing configuration (moved from tasks.publishPlugin)
    publishing {
        token.set(System.getenv("PUBLISH_TOKEN"))
        // channels.set(listOf("default")) // Specify channel(s) if needed
    }

    // runIde { ... } // Configure runIde task if needed (e.g., specific IDE path)
}