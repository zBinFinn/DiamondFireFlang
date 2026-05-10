package com.zbinfinn.intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.util.PathUtil
import com.zbinfinn.lsp.FlangLanguageServer
import java.io.File

internal class FlangLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (file.extension == "fl") {
            serverStarter.ensureServerStarted(FlangLspServerDescriptor(project))
        }
    }
}

private class FlangLspServerDescriptor(
    project: Project,
) : ProjectWideLspServerDescriptor(project, "DiamondFire Flang") {
    override fun isSupportedFile(file: VirtualFile): Boolean =
        file.extension == "fl"

    override fun createCommandLine(): GeneralCommandLine {
        val javaExecutable = File(System.getProperty("java.home"), "bin/java").absolutePath
        return GeneralCommandLine(javaExecutable)
            .withParameters("-cp", lspClasspath(), "com.zbinfinn.lsp.FlangLanguageServerKt")
            .withWorkDirectory(project.basePath)
    }

    private fun lspClasspath(): String {
        val lspJar = File(PathUtil.getJarPathForClass(FlangLanguageServer::class.java))
        val classpathRoots = lspJar.parentFile
            ?.listFiles { file -> file.isFile && file.extension == "jar" }
            ?.mapTo(linkedSetOf()) { FileUtil.toSystemDependentName(it.absolutePath) }
            ?: linkedSetOf(FileUtil.toSystemDependentName(lspJar.absolutePath))
        return classpathRoots.joinToString(File.pathSeparator)
    }
}
