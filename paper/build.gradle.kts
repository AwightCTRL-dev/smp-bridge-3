plugins {
    java
}

val paperApi = providers.gradleProperty("paperApiVersion").getOrElse("1.21.4-R0.1-SNAPSHOT")
val javaRelease = providers.gradleProperty("paperJavaRelease").getOrElse("21").toInt()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.3")
}

sourceSets {
    main {
        java.srcDir("../common/src/main/java")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaRelease)
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveFileName.set("SMPBridge-Paper.jar")
}
