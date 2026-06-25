package com.github.huginnandmuninn52.filefinder

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Paths

class SwitchEnRuAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            showNotification("No project found", NotificationType.WARNING)
            return
        }

        fun isFileOpen(file: VirtualFile): Boolean {
            return FileEditorManager.getInstance(project).getEditors(file).isNotEmpty()
        }

        fun getFolderPrefix(file: VirtualFile): String {
            val path = file.path
            return when {
                path.contains("/en/") -> "en"
                path.contains("/ru/") -> "ru"
                else -> ""
            }
        }

        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        val filesToProcess: List<VirtualFile> = if (!selectedFiles.isNullOrEmpty()) {
            val filtered = selectedFiles.filter { !it.isDirectory }
            if (filtered.isEmpty()) {
                showNotification("Selected items are folders. Please select files.", NotificationType.WARNING)
                return
            }
            filtered
        } else {
            val editorFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            if (editorFile != null && !editorFile.isDirectory) {
                listOf(editorFile)
            } else {
                showNotification("No file selected or opened. Please select files in Project view or open a file in editor.", NotificationType.WARNING)
                return
            }
        }

        val sourceWasOpenMap = filesToProcess.associateWith { isFileOpen(it) }
        val sourceScrollOffsets = mutableMapOf<VirtualFile, Int>()

        var totalOpened = 0
        val targetFileNames = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (currentFile in filesToProcess) {
            val wasSourceOpen = sourceWasOpenMap[currentFile] ?: false

            val currentEditors = FileEditorManager.getInstance(project).getEditors(currentFile)
            val currentTextEditor = currentEditors.filterIsInstance<TextEditor>().firstOrNull()
            val caretLine = currentTextEditor?.editor?.caretModel?.logicalPosition?.line
            val scrollOffset = currentTextEditor?.editor?.scrollingModel?.verticalScrollOffset

            if (scrollOffset != null) {
                sourceScrollOffsets[currentFile] = scrollOffset
            }

            FileEditorManager.getInstance(project).openFile(currentFile, true)
            if (!wasSourceOpen) {
                totalOpened++
            }

            val currentPath = Paths.get(currentFile.path)
            var parent = currentPath.parent
            var found = false
            var targetPath = currentPath

            while (parent != null && !found) {
                val folderName = parent.fileName?.toString()
                when (folderName) {
                    "en" -> {
                        val newParent = parent.parent.resolve("ru")
                        targetPath = newParent.resolve(parent.relativize(currentPath))
                        found = true
                    }
                    "ru" -> {
                        val newParent = parent.parent.resolve("en")
                        targetPath = newParent.resolve(parent.relativize(currentPath))
                        found = true
                    }
                }
                parent = parent.parent
            }

            if (!found) {
                errors.add("No 'en' or 'ru' folder found for ${currentFile.name}")
                continue
            }

            val targetFile = LocalFileSystem.getInstance().findFileByPath(targetPath.toString())
            if (targetFile != null && !targetFile.isDirectory) {
                val wasTargetOpen = isFileOpen(targetFile)
                FileEditorManager.getInstance(project).openFile(targetFile, true)
                if (!wasTargetOpen) {
                    totalOpened++
                }

                val prefix = getFolderPrefix(targetFile)
                targetFileNames.add(if (prefix.isNotEmpty()) "$prefix/${targetFile.name}" else targetFile.name)

                val savedOffset = sourceScrollOffsets[currentFile]
                ApplicationManager.getApplication().invokeLater({
                    val targetEditors = FileEditorManager.getInstance(project).getEditors(targetFile)
                    val targetTextEditor = targetEditors.filterIsInstance<TextEditor>().firstOrNull()
                    targetTextEditor?.let { editor ->
                        if (caretLine != null) {
                            editor.editor.caretModel.moveToLogicalPosition(LogicalPosition(caretLine, 0))
                        }
                        if (savedOffset != null) {
                            editor.editor.scrollingModel.scrollVertically(savedOffset)
                        }
                    }
                }, ModalityState.nonModal())
            } else {
                errors.add("Corresponding file not found for ${currentFile.name}")
            }
        }

        val message = buildString {
            when {
                filesToProcess.size > 1 -> {
                    append("Opened $totalOpened files")
                    if (errors.isNotEmpty()) {
                        append("\nErrors:\n")
                        append(errors.joinToString("\n"))
                    }
                }
                filesToProcess.size == 1 -> {
                    val currentFile = filesToProcess[0]
                    val wasSourceOpen = sourceWasOpenMap[currentFile] ?: false
                    if (wasSourceOpen) {
                        if (targetFileNames.isNotEmpty()) {
                            append("Opened: ${targetFileNames[0]}")
                        } else {
                            append("No target file opened")
                        }
                        if (errors.isNotEmpty()) {
                            append(".<br/>Errors:\n")
                            append(errors.joinToString("\n"))
                        }
                    } else {
                        append("Opened $totalOpened files")
                        if (errors.isNotEmpty()) {
                            append("<br/>Errors:\n")
                            append(errors.joinToString("\n"))
                        }
                    }
                }
                else -> {
                    append("No files were processed.")
                }
            }
        }
        showNotification(message, if (errors.isNotEmpty()) NotificationType.WARNING else NotificationType.INFORMATION)
    }

    private fun showNotification(content: String, type: NotificationType) {
        Notifications.Bus.notify(
            Notification("SwitchEnRu", "Switch en/ru", content, type)
        )
    }
}