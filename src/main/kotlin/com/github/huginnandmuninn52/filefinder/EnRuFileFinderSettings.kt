package com.github.huginnandmuninn52.filefinder

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level settings for the En-Ru File Finder plugin,
 * shown under Settings | Tools | En-Ru File Finder.
 */
@Service
@State(name = "EnRuFileFinderSettings", storages = [Storage("enRuFileFinder.xml")])
class EnRuFileFinderSettings : PersistentStateComponent<EnRuFileFinderSettings.State> {

    /** Where the counterpart file should be opened. */
    enum class OpenMode {
        /** Open in a new editor tab in the current tab group (default). */
        NEW_TAB,

        /** Open in a right split of the current editor (like the "Split Right" action). */
        RIGHT_SPLIT
    }

    class State {
        var openMode: OpenMode = OpenMode.NEW_TAB
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var openMode: OpenMode
        get() = myState.openMode
        set(value) {
            myState.openMode = value
        }

    companion object {
        fun getInstance(): EnRuFileFinderSettings =
            ApplicationManager.getApplication().getService(EnRuFileFinderSettings::class.java)
    }
}
