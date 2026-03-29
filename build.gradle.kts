plugins {
    java
    application
}

group = "com.soulshinygame.bot"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.soulshinygame.bot.Main")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.twitch4j:twitch4j:1.19.0")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("com.j256.ormlite:ormlite-jdbc:6.1")
    implementation("io.github.cdimascio:dotenv-java:3.0.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    implementation("org.slf4j:slf4j-simple:2.0.12")
}

// Fat JAR — incluye todas las dependencias y el manifest correcto
tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.soulshinygame.bot.Main"
    }
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}