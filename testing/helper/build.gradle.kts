plugins {
    java
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.133-stable")
    // The plugin under test, built first with ./gradlew build from the root.
    compileOnly(fileTree("../../build/libs") { include("FrozenVillagers-*.jar") })
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
