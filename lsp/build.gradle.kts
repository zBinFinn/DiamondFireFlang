plugins {
    id("flang.kotlin-jvm-conventions")
    application
}

dependencies {
    implementation(project(":compiler"))
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0")
}

application {
    mainClass.set("com.zbinfinn.lsp.FlangLanguageServerKt")
}
