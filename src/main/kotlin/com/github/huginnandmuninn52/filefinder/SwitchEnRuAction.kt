package com.github.huginnandmuninn52.filefinder

import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.ui.Messages
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

        fun createFileWithParents(sourcFile: VirtualFile, targetPath: java.nio.file.Path): VirtualFile? {
            return try {
                WriteAction.compute<VirtualFile?, Exception> {
                    // Create parent directories if they don't exist
                    var currentDir = LocalFileSystem.getInstance()
                        .findFileByPath(targetPath.root.toString())
                        ?: return@compute null

                    for (part in targetPath.parent) {
                        val partName = part.toString()
                        var child = currentDir.findChild(partName)
                        if (child == null) {
                            child = currentDir.createChildDirectory(this, partName)
                        }
                        currentDir = child
                    }

                    // Create the file and copy contents
                    val newFile = currentDir.createChildData(this, targetPath.fileName.toString())
                    newFile.setBinaryContent(sourcFile.contentsToByteArray())
                    newFile
                }
            } catch (_: Exception) {
                null
            }
        }

        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val errors = mutableListOf<String>()

        val filesToProcess: List<VirtualFile>

        if (!selectedFiles.isNullOrEmpty()) {
            val folders = selectedFiles.filter { it.isDirectory }
            val files = selectedFiles.filter { !it.isDirectory }

            for (folder in folders) {
                val currentPath = Paths.get(folder.path)
                var parent = currentPath.parent
                var found = false
                var targetPath = currentPath

                while (parent != null && !found) {
                    val folderName = parent.fileName?.toString()
                    when (folderName) {
                        "en" -> {
                            targetPath = parent.parent.resolve("ru").resolve(parent.relativize(currentPath))
                            found = true
                        }
                        "ru" -> {
                            targetPath = parent.parent.resolve("en").resolve(parent.relativize(currentPath))
                            found = true
                        }
                    }
                    parent = parent.parent
                }

                if (found) {
                    val targetFolder = LocalFileSystem.getInstance().findFileByPath(targetPath.toString())
                    if (targetFolder != null && targetFolder.isDirectory) {
                        ProjectView.getInstance(project).select(null, targetFolder, true)
                    } else {
                        errors.add("Corresponding folder not found for ${folder.name}")
                    }
                } else {
                    errors.add("No 'en' or 'ru' folder found for ${folder.name}")
                }
            }

            if (files.isEmpty()) {
                if (errors.isNotEmpty()) {
                    showNotification(errors.joinToString("\n"), NotificationType.WARNING)
                }
                return
            }
            filesToProcess = files
        } else {
            val editorFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            if (editorFile != null && !editorFile.isDirectory) {
                filesToProcess = listOf(editorFile)
            } else {
                showNotification("No file selected or opened. Please select files in Project view or open a file in editor.", NotificationType.WARNING)
                return
            }
        }

        val sourceWasOpenMap = filesToProcess.associateWith { isFileOpen(it) }

        data class SourcePosition(val caretLine: Int, val relativePosition: Float)
        val sourcePositions = mutableMapOf<VirtualFile, SourcePosition>()

        var totalOpened = 0
        var totalCreated = 0
        val targetFileNames = mutableListOf<String>()

        for (currentFile in filesToProcess) {
            val wasSourceOpen = sourceWasOpenMap[currentFile] ?: false

            val currentEditors = FileEditorManager.getInstance(project).getEditors(currentFile)
            val currentTextEditor = currentEditors.filterIsInstance<TextEditor>().firstOrNull()

            if (currentTextEditor != null) {
                val caretLine = currentTextEditor.editor.caretModel.logicalPosition.line
                val visibleArea = currentTextEditor.editor.scrollingModel.visibleArea
                val caretY = currentTextEditor.editor.visualPositionToXY(
                    currentTextEditor.editor.caretModel.visualPosition
                ).y
                val relativePosition = if (visibleArea.height > 0) {
                    (caretY - visibleArea.y).toFloat() / visibleArea.height.toFloat()
                } else {
                    0f
                }
                sourcePositions[currentFile] = SourcePosition(caretLine, relativePosition)
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

            var targetFile = LocalFileSystem.getInstance().findFileByPath(targetPath.toString())

            // Target file does not exist — create it with full contents
// Target file does not exist — ask user to create it
            if (targetFile == null || targetFile.isDirectory) {
                val prefix = getFolderPrefix(currentFile).let {
                    if (it == "en") "ru" else "en"
                }
                val confirmed = Messages.showYesNoDialog(
                    project,
                    "No $prefix/${currentFile.name} found. Create?",
                    "File Not Found",
                    Messages.getQuestionIcon()
                ) == Messages.YES

                if (!confirmed) continue

                val createdFile = createFileWithParents(currentFile, targetPath)
                if (createdFile != null) {
                    targetFile = createdFile
                    totalCreated++
                } else {
                    errors.add("Failed to create file for ${currentFile.name}")
                    continue
                }
            }

            val wasTargetOpen = isFileOpen(targetFile)
            val prefix = getFolderPrefix(targetFile)
            targetFileNames.add(if (prefix.isNotEmpty()) "$prefix/${targetFile.name}" else targetFile.name)

            val sourcePosition = sourcePositions[currentFile]
            val targetFileRef = targetFile

            fun applyCaretAndScroll(editor: TextEditor) {
                if (sourcePosition != null) {
                    editor.editor.caretModel.moveToLogicalPosition(
                        LogicalPosition(sourcePosition.caretLine, 0)
                    )
                    ApplicationManager.getApplication().invokeLater({
                        val targetVisibleArea = editor.editor.scrollingModel.visibleArea
                        val targetCaretY = editor.editor.visualPositionToXY(
                            editor.editor.caretModel.visualPosition
                        ).y
                        val desiredScrollY = targetCaretY -
                                (sourcePosition.relativePosition * targetVisibleArea.height).toInt()
                        editor.editor.scrollingModel.scrollVertically(desiredScrollY.coerceAtLeast(0))
                    }, ModalityState.nonModal())
                }
            }

            if (wasTargetOpen) {
                // File is already open — apply position immediately
                val targetTextEditor = FileEditorManager.getInstance(project)
                    .getEditors(targetFileRef).filterIsInstance<TextEditor>().firstOrNull()
                targetTextEditor?.let { applyCaretAndScroll(it) }
                FileEditorManager.getInstance(project).openFile(targetFileRef, true)
            } else {
                // File is not yet open — wait for the fileOpened event
                val connection = project.messageBus.connect()
                connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                        if (file != targetFileRef) return
                        connection.disconnect()
                        val targetTextEditor = source.getEditors(file)
                            .filterIsInstance<TextEditor>().firstOrNull()
                        targetTextEditor?.let { applyCaretAndScroll(it) }
                    }
                })
                FileEditorManager.getInstance(project).openFile(targetFileRef, true)
            }

            if (!wasTargetOpen) {
                totalOpened++
            }
        }

        val message = buildString {
            when {
                filesToProcess.size > 1 -> {
                    append("Opened $totalOpened files")
                    if (totalCreated > 0) append(", created $totalCreated files")
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
                            val created = totalCreated > 0
                            append(if (created) "Created and opened: ${targetFileNames[0]}" else "Opened: ${targetFileNames[0]}")
                        } else {
                            append("No target file opened")
                        }
                        if (errors.isNotEmpty()) {
                            append(".<br/>Errors:\n")
                            append(errors.joinToString("\n"))
                        }
                    } else {
                        append("Opened $totalOpened files")
                        if (totalCreated > 0) append(", created $totalCreated files")
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