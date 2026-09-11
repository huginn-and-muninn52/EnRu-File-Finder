package com.github.huginnandmuninn52.filefinder

import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManagerEx
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.impl.DiffSettingsHolder
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.fragmented.UnifiedDiffTool
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.ide.DataManager
import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Pair as IJPair
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.vcsUtil.VcsUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Component
import java.nio.file.Paths

class SwitchEnRuAction : AnAction() {

    companion object {
        // Tracks diff tabs our shortcut has opened, per project, keyed by the real
        // target file's path — so repeated presses reuse/focus an existing tab
        // instead of endlessly creating new ones.
        private val openedDiffTabs = mutableMapOf<Project, MutableMap<String, VirtualFile>>()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            showNotification("No project found", NotificationType.WARNING)
            return
        }

        // If the shortcut was triggered while focus was inside a VCS diff view,
        // handle it separately: open the diff for the counterpart en/ru file instead
        // of just opening the plain file.
        // GitLab MR (and similar) diff tabs don't provide CURRENT_CHANGE, but the diff
        // request itself carries the Change it was built from — fall back to it.
        // Combined diff tabs (e.g. "Repository Diff" from the Git tool window's Log) don't
        // provide DIFF_REQUEST either, but they do expose the focused block's viewer via
        // DIFF_VIEWER, whose request carries the same CHANGE_KEY — fall back to that too.
        val currentChange = e.getData(VcsDataKeys.CURRENT_CHANGE)
            ?: e.getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(ChangeDiffRequestProducer.CHANGE_KEY)
            ?: (e.getData(DiffDataKeys.DIFF_VIEWER) as? DiffViewerBase)
                ?.request?.getUserData(ChangeDiffRequestProducer.CHANGE_KEY)
        if (currentChange != null) {
            handleDiffContext(e, project, currentChange)
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
            val enableBlame = isBlameEnabled(currentTextEditor?.editor)

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
                applyBlameState(targetTextEditor?.editor, enableBlame)
                openTargetFile(project, targetFileRef)
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
                        applyBlameState(targetTextEditor?.editor, enableBlame)
                    }
                })
                openTargetFile(project, targetFileRef)
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

    /**
     * Handles the shortcut when it's triggered with focus inside a VCS diff view
     * (e.g. a diff opened from the Commit / Local Changes tool window).
     *
     * Detected via [VcsDataKeys.CURRENT_CHANGE] being non-null — confirmed empirically
     * to be the key IntelliJ populates for a single-file diff context, as opposed to
     * [VcsDataKeys.CHANGES] which is for multi-file selections in a changes list.
     * GitLab Merge Request diff tabs don't provide CURRENT_CHANGE; for them the Change
     * is taken from the diff request's [ChangeDiffRequestProducer.CHANGE_KEY] user data,
     * and the counterpart diff is rebuilt at the same revisions
     * (see [buildCounterpartRevisionChange]). Combined diff tabs ("Repository Diff"
     * opened from the Git tool window's Log) provide neither CURRENT_CHANGE nor
     * DIFF_REQUEST; there the Change comes from the focused block's viewer
     * ([DiffDataKeys.DIFF_VIEWER]) via its request's CHANGE_KEY.
     *
     * [CommonDataKeys.VIRTUAL_FILE] resolves to the real underlying file inside a
     * side-by-side diff viewer, but in the unified (onesided) viewer it is either null
     * or a synthetic diff file, so we fall back to extracting the file from the Change
     * itself when the context file is unusable.
     *
     * Caret line is preserved via [DiffUserDataKeys.SCROLL_TO_LINE], scrolling
     * [Side.RIGHT] (the "after"/working-copy pane, by VCS diff convention) to roughly
     * where the caret was in the source diff. This is an approximation — no relative
     * scroll-offset math like the plain-file flow, just "scroll this line into view" —
     * and the Side.RIGHT assumption should be double-checked against real usage.
     */
    private fun handleDiffContext(e: AnActionEvent, project: Project, change: Change) {
        val contextFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?.takeIf { it.isInLocalFileSystem && !it.isDirectory }
        val currentFile = contextFile
            ?: change.virtualFile
            ?: (change.afterRevision ?: change.beforeRevision)?.file?.let {
                LocalFileSystem.getInstance().findFileByPath(it.path)
            }
            ?: run {
                showNotification("Could not determine current file from diff", NotificationType.WARNING)
                return
            }

        // Capture the source viewer mode now, on EDT, so the counterpart diff can be
        // opened in the same viewer (Unified vs Side-by-side). We must NOT use
        // DiffUserDataKeysEx.FORCE_DIFF_TOOL for that: forcing restricts the processor
        // to a single tool and hides the viewer-mode selector from the new tab's toolbar.
        val preferredDiffTool = when (e.getData(DiffDataKeys.DIFF_VIEWER)) {
            is UnifiedDiffViewer -> UnifiedDiffTool.INSTANCE
            is DiffViewerBase -> SimpleDiffTool.INSTANCE
            else -> null
        }

        // Capture caret position now, on EDT, before dropping into a background task.
        val currentEditor = e.getData(CommonDataKeys.EDITOR)
        val enableBlame = isBlameEnabled(currentEditor)
        val currentLine = currentEditor?.caretModel?.logicalPosition?.line
        val relativePosition = if (currentEditor != null) {
            val visibleArea = currentEditor.scrollingModel.visibleArea
            val caretY = currentEditor.visualPositionToXY(
                currentEditor.caretModel.visualPosition
            ).y
            if (visibleArea.height > 0) {
                (caretY - visibleArea.y).toFloat() / visibleArea.height.toFloat()
            } else 0f
        } else 0f

        val currentPath = Paths.get(currentFile.path)
        var parent = currentPath.parent
        var found = false
        var targetPath = currentPath

        while (parent != null && !found) {
            when (parent.fileName?.toString()) {
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

        if (!found) {
            showNotification("No 'en' or 'ru' folder found for ${currentFile.name}", NotificationType.WARNING)
            return
        }

        val targetVFile = LocalFileSystem.getInstance().findFileByPath(targetPath.toString())
        if (targetVFile == null || targetVFile.isDirectory) {
            showNotification("Counterpart file not found: $targetPath", NotificationType.WARNING)
            return
        }
        val fem = FileEditorManager.getInstance(project)
        val tabsForProject = openedDiffTabs.getOrPut(project) { mutableMapOf() }
        // Include the "after" revision in the cache key so a local-changes diff and an
        // MR-revision diff of the same file don't reuse each other's tab.
        val tabKey = targetPath.toString() + "@" + (change.afterRevision?.revisionNumber?.asString() ?: "local")
        val cachedTab = tabsForProject[tabKey]
        if (cachedTab != null && fem.isFileOpen(cachedTab)) {
            fem.openFile(cachedTab, true)
            val targetEditors = mutableListOf<Editor>()
            for (cachedEditor in fem.getEditors(cachedTab)) {
                if (cachedEditor is TextEditor) {
                    targetEditors.add(cachedEditor.editor)
                }
                targetEditors.addAll(findEditorsInComponent(cachedEditor.component))
            }
            val targetDoc = FileDocumentManager.getInstance().getDocument(targetVFile)
            val editorToSync = targetEditors.firstOrNull { it.document == targetDoc }
                ?: targetEditors.firstOrNull()
            if (editorToSync != null) {
                applyBlameState(editorToSync, enableBlame)
                if (currentLine != null) {
                    editorToSync.caretModel.moveToLogicalPosition(
                        LogicalPosition(currentLine, 0)
                    )
                    ApplicationManager.getApplication().invokeLater({
                        val targetVisibleArea = editorToSync.scrollingModel.visibleArea
                        val targetCaretY = editorToSync.visualPositionToXY(
                            editorToSync.caretModel.visualPosition
                        ).y
                        val desiredScrollY = targetCaretY -
                                (relativePosition * targetVisibleArea.height).toInt()
                        editorToSync.scrollingModel.scrollVertically(desiredScrollY.coerceAtLeast(0))
                    }, ModalityState.nonModal())
                }
            }
            return
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading diff") {
            override fun run(indicator: ProgressIndicator) {
                // For local-changes diffs (after side is the working copy) look the counterpart
                // change up in the change lists. For revision-based diffs (e.g. GitLab MR tabs)
                // rebuild a Change for the counterpart file at the same VCS revisions instead.
                // This must run in the background: building revision content may trigger a
                // synchronous VCS repository lookup, which is forbidden on the EDT.
                val isLocalDiff = change.afterRevision is CurrentContentRevision || change.beforeRevision == null
                val counterpartChange: Change? = if (isLocalDiff) {
                    ChangeListManager.getInstance(project).getChange(targetVFile)
                } else {
                    buildCounterpartRevisionChange(project, change, targetVFile)
                        ?: ChangeListManager.getInstance(project).getChange(targetVFile)
                }
                if (counterpartChange == null) {
                    ApplicationManager.getApplication().invokeLater {
                        showNotification("Counterpart file has no pending changes to diff", NotificationType.WARNING)
                    }
                    return
                }

                val producer = ChangeDiffRequestProducer.create(project, counterpartChange)
                if (producer == null) {
                    ApplicationManager.getApplication().invokeLater {
                        showNotification("Could not build diff for counterpart file", NotificationType.WARNING)
                    }
                    return
                }

                val request = try {
                    producer.process(UserDataHolderBase(), indicator)
                } catch (ex: ProcessCanceledException) {
                    throw ex
                } catch (ex: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        showNotification("Failed to load diff: ${ex.message}", NotificationType.WARNING)
                    }
                    return
                }

                if (currentLine != null) {
                    request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, IJPair.create(Side.RIGHT, currentLine))
                }
                val chain = SimpleDiffRequestChain(request)

                ApplicationManager.getApplication().invokeLater {
                    // Preselect the source viewer mode by moving its tool to the front of the
                    // default-place tools order — the same thing the platform does when the
                    // user toggles the mode manually. Unlike FORCE_DIFF_TOOL, this keeps the
                    // Unified/Side-by-side selector available in the new tab.
                    if (preferredDiffTool != null) {
                        val settings = DiffSettingsHolder.DiffSettings.getSettings(null)
                        val toolName = preferredDiffTool.javaClass.canonicalName
                        settings.diffToolsOrder =
                            listOf(toolName) + settings.diffToolsOrder.filter { it != toolName }
                    }
                    // Honor the "Open new file: In right split" setting for diff tabs too:
                    // a diff tab is backed by a ChainDiffVirtualFile, which can be opened
                    // in an editor split just like a plain file. Only applicable when diffs
                    // open as editor tabs (not in a separate window) — same check the
                    // internal DiffEditorTabFilesUtil.isDiffInEditor performs, done via the
                    // public AdvancedSettings API to stay off internal classes.
                    val splitters =
                        if (EnRuFileFinderSettings.getInstance().openMode == EnRuFileFinderSettings.OpenMode.RIGHT_SPLIT &&
                            AdvancedSettings.getBoolean("show.diff.as.editor.tab")
                        ) FileEditorManagerEx.getInstanceEx(project).splitters else null
                    var openedInSplit = false
                    if (splitters != null) {
                        val diffFile = ChainDiffVirtualFile(chain, targetVFile.name)
                        // openInRightSplit(file, requestFocus) is the same public API the
                        // platform's "Open in Right Split" action uses; unlike
                        // EditorWindow.split it is binary-stable across IDE versions.
                        if (splitters.openInRightSplit(diffFile, true) != null) {
                            // Cache the diff file directly: fem.selectedEditor may still point
                            // at the source window right after the split, which would leave the
                            // tab uncached and make every press open yet another split.
                            tabsForProject[tabKey] = diffFile
                            openedInSplit = true
                        }
                    }
                    if (!openedInSplit) {
                        DiffManagerEx.getInstance().showDiffBuiltin(project, chain, DiffDialogHints.DEFAULT)
                    }
                    fem.selectedEditor?.let { opened ->
                        if (!openedInSplit) {
                            tabsForProject[tabKey] = opened.file
                        }
                        val editors = mutableListOf<Editor>()
                        if (opened is TextEditor) {
                            editors.add(opened.editor)
                        }
                        editors.addAll(findEditorsInComponent(opened.component))
                        val targetDoc = FileDocumentManager.getInstance().getDocument(targetVFile)
                        applyBlameState(
                            editors.firstOrNull { it.document == targetDoc }
                                ?: editors.firstOrNull(),
                            enableBlame
                        )
                    }
                }
            }
        })
    }

    /**
     * Builds a [Change] for [targetVFile] pinned to the same VCS revisions as the
     * source [change]. Used for revision-based diffs (GitLab Merge Request tabs,
     * git-log diffs, etc.) where the counterpart has no local pending change.
     * Uses the VCS-agnostic [com.intellij.openapi.vcs.diff.DiffProvider], so it works
     * for any VCS that supports fetching file content by revision number.
     *
     * Must be called on a background thread: [com.intellij.openapi.vcs.diff.DiffProvider.createFileContent]
     * may perform a synchronous repository lookup, which is forbidden on the EDT.
     */
    private fun buildCounterpartRevisionChange(project: Project, change: Change, targetVFile: VirtualFile): Change? {
        val vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(targetVFile) ?: return null
        val diffProvider = vcs.diffProvider ?: return null

        fun counterpartRevision(rev: ContentRevision?): ContentRevision? = when (rev) {
            null -> null
            is CurrentContentRevision -> CurrentContentRevision(VcsUtil.getFilePath(targetVFile))
            else -> diffProvider.createFileContent(rev.revisionNumber, targetVFile)
        }

        val before = counterpartRevision(change.beforeRevision)
        val after = counterpartRevision(change.afterRevision)
        if (before == null && after == null) return null
        return Change(before, after)
    }

    /**
     * Opens [file] according to the "Open new file" setting
     * (Settings | Tools | En-Ru File Finder): either as a regular tab in the
     * current tab group, or in a right split of the current editor window —
     * the same thing the "Split Right" action does. If the file is already
     * open somewhere, it is simply focused.
     */
    private fun openTargetFile(project: Project, file: VirtualFile) {
        val fem = FileEditorManager.getInstance(project)
        if (EnRuFileFinderSettings.getInstance().openMode == EnRuFileFinderSettings.OpenMode.RIGHT_SPLIT &&
            !fem.isFileOpen(file)
        ) {
            // Public, binary-stable equivalent of "Split Right" (used by the platform's
            // "Open in Right Split" action); EditorWindow.split is avoided because its
            // JVM signature changes between IDE versions.
            val window = FileEditorManagerEx.getInstanceEx(project).splitters.openInRightSplit(file, true)
            if (window != null) {
                return
            }
        }
        fem.openFile(file, true)
    }

    private fun findEditorsInComponent(root: Component): List<Editor> {
        val result = mutableListOf<Editor>()
        for (editor in EditorFactory.getInstance().getAllEditors()) {
            var component: Component? = editor.component
            while (component != null) {
                if (component === root) {
                    result.add(editor)
                    break
                }
                component = component.parent
            }
        }
        return result
    }

    private val annotateToggleAction: ToggleAction?
        get() = ActionManager.getInstance().getAction("Annotate") as? ToggleAction

    private fun annotateEvent(editor: Editor): AnActionEvent {
        return AnActionEvent.createEvent(
            DataManager.getInstance().getDataContext(editor.component),
            Presentation(),
            "EnRu-File-Finder",
            ActionUiKind.NONE,
            null
        )
    }

    private fun isBlameEnabled(editor: Editor?): Boolean =
        if (editor == null) false else editor.gutter.isAnnotationsShown()

    private fun applyBlameState(editor: Editor?, enabled: Boolean) {
        if (editor == null) return
        val action = annotateToggleAction ?: return
        try {
            val shown = editor.gutter.isAnnotationsShown()
            if (shown == enabled) return
            action.setSelected(annotateEvent(editor), enabled)
        } catch (_: Throwable) {
        }
    }

    private fun showNotification(content: String, type: NotificationType) {
        // Regular (INFORMATION) messages and warnings/errors go to separate notification
        // groups, so users can disable the regular ones but keep warnings/errors.
        val groupId = if (type == NotificationType.INFORMATION) "En-Ru File Finder Regular" else "En-Ru File Finder Problems"
        Notifications.Bus.notify(
            Notification(groupId, "Switch en/ru", content, type)
        )
    }
}