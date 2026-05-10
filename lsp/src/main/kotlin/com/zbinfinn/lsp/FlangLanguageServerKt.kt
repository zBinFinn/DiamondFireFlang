@file:JvmName("FlangLanguageServerKt")

package com.zbinfinn.lsp

import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient

fun main() {
    val server = FlangLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out)
    server.connect(launcher.remoteProxy as LanguageClient)
    launcher.startListening().get()
}
