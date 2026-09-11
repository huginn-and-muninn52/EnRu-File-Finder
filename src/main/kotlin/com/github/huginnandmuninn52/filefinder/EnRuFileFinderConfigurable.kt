package com.github.huginnandmuninn52.filefinder

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.panel

/**
 * Settings page shown under Settings | Tools | En-Ru File Finder.
 */
class EnRuFileFinderConfigurable : BoundConfigurable("En-Ru File Finder") {

    override fun createPanel(): DialogPanel {
        val settings = EnRuFileFinderSettings.getInstance()
        return panel {
            buttonsGroup("Open new file:") {
                row {
                    radioButton("In new tab", EnRuFileFinderSettings.OpenMode.NEW_TAB)
                }
                row {
                    radioButton("In right split", EnRuFileFinderSettings.OpenMode.RIGHT_SPLIT)
                }
            }.bind({ settings.openMode }, { settings.openMode = it })
        }
    }
}
