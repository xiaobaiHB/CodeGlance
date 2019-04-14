/*
 * Copyright © 2013, Adam Scarr
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.vektah.codeglance

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.JBColor
import net.vektah.codeglance.concurrent.DirtyLock
import net.vektah.codeglance.config.Config
import net.vektah.codeglance.config.ConfigService
import net.vektah.codeglance.render.*

import javax.swing.*
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.lang.ref.SoftReference

/**
 * This JPanel gets injected into editor windows and renders a image generated by GlanceFileRenderer
 */
class GlancePanel(private val project: Project, fileEditor: FileEditor, private val container: JPanel, private val runner: TaskRunner) : JPanel(), VisibleAreaListener {
    private val editor = (fileEditor as TextEditor).editor
    private var mapRef = SoftReference<Minimap>(null)
    private val configService = ServiceManager.getService(ConfigService::class.java)
    private var config: Config = configService.state!!
    private var lastFoldCount = -1
    private var buf: BufferedImage? = null
    private val renderLock = DirtyLock()
    private val scrollstate = ScrollState()
    private val scrollbar = Scrollbar(editor, scrollstate)

    // Anonymous Listeners that should be cleaned up.
    private val componentListener: ComponentListener
    private val documentListener: DocumentListener
    private val selectionListener: SelectionListener = object: SelectionListener {
        override fun selectionChanged(e: SelectionEvent) {
            repaint()
        }
    }

    private val isDisabled: Boolean
        get() = config.disabled || editor.document.textLength > config.maxFileSize || editor.document.lineCount < config.minLineCount || container.width < config.minWindowWidth

    private val onConfigChange = {
        updateImage()
        updateSize()
        this@GlancePanel.revalidate()
        this@GlancePanel.repaint()
    }

    init {
        componentListener = object : ComponentAdapter() {
            override fun componentResized(componentEvent: ComponentEvent?) {
                updateSize()
                scrollstate.setVisibleHeight(height)
                this@GlancePanel.revalidate()
                this@GlancePanel.repaint()
            }
        }
        container.addComponentListener(componentListener)

        documentListener = object : DocumentAdapter() {
            override fun documentChanged(event: DocumentEvent) {
                updateImage()
            }
        }
        editor.document.addDocumentListener(documentListener)

        configService.onChange(onConfigChange)

        editor.scrollingModel.addVisibleAreaListener(this)

        editor.selectionModel.addSelectionListener(selectionListener)
        updateSize()
        updateImage()

        isOpaque = false
        layout = BorderLayout()
        add(scrollbar)
    }


    /**
     * Adjusts the panels size to be a percentage of the total window
     */
    private fun updateSize() {
        if (isDisabled) {
            preferredSize = Dimension(0, 0)
        } else {
            val size = Dimension(config.width, 0)
            preferredSize = size
        }
    }

    // the minimap is held by a soft reference so the GC can delete it at any time.
    // if its been deleted and we want it again (active tab) we recreate it.
    private fun getOrCreateMap() : Minimap? {
        var map = mapRef.get()

        if (map == null) {
            map = Minimap(configService.state!!)
            mapRef = SoftReference<Minimap>(map)
        }

        return map
    }

    /**
     * Fires off a new task to the worker thread. This should only be called from the ui thread.
     */
    private fun updateImage() {
        if (isDisabled) return
        if (project.isDisposed) return

        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return

        val map = getOrCreateMap() ?: return
        if (!renderLock.acquire()) return

        val hl = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, project, file.virtualFile)

        val text = editor.document.text
        val folds = Folds(editor.foldingModel.allFoldRegions)

        runner.run {
            map.update(text, editor.colorsScheme, hl, folds)
            scrollstate.setDocumentSize(config.width, map.height)

            renderLock.release()

            if (renderLock.dirty) {
                updateImageSoon()
                renderLock.clean()
            }

            repaint()
        }
    }

    private fun updateImageSoon() = SwingUtilities.invokeLater { updateImage() }

    fun paintLast(gfx: Graphics?) {
        val g = gfx as Graphics2D


        if (buf != null) {
            g.drawImage(buf,
                0, 0, buf!!.width, buf!!.height,
                0, 0, buf!!.width, buf!!.height,
                null)
        }
        paintSelections(g)
        scrollbar.paint(gfx)
    }

    override fun paint(gfx: Graphics?) {
        if (renderLock.locked) {
            paintLast(gfx)
            return
        }

        val minimap = mapRef.get()
        if (minimap == null) {
            updateImageSoon()
            paintLast(gfx)
            return
        }

        if (buf == null || buf?.width!! < width || buf?.height!! < height) {
            buf = BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR)
        }

        val g = buf!!.createGraphics()

        g.composite = AlphaComposite.getInstance(AlphaComposite.CLEAR)
        g.fillRect(0, 0, width, height)
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER)

        if (editor.document.textLength != 0) {
            g.drawImage(
                minimap.img,
                0, 0, scrollstate.documentWidth, scrollstate.drawHeight,
                0, scrollstate.visibleStart, scrollstate.documentWidth, scrollstate.visibleEnd,
                null
            )
        }

        paintSelections(gfx as Graphics2D)
        gfx.drawImage(buf, 0, 0, null)
        scrollbar.paint(gfx)
    }

    private fun paintSelection(g: Graphics2D, startByte: Int, endByte: Int) {
        val start = editor.offsetToVisualPosition(startByte)
        val end = editor.offsetToVisualPosition(endByte)

        val sX = start.column
        val sY = (start.line + 1) * config.pixelsPerLine - scrollstate.visibleStart
        val eX = end.column
        val eY = (end.line + 1) * config.pixelsPerLine - scrollstate.visibleStart

        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.80f)
        g.color = editor.colorsScheme.getColor(ColorKey.createColorKey("SELECTION_BACKGROUND", JBColor.BLUE))

        // Single line is real easy
        if (start.line == end.line) {
            g.fillRect(
                sX,
                sY,
                eX - sX,
                config.pixelsPerLine
            )
        } else {
            // Draw the line leading in
            g.fillRect(sX, sY, width - sX, config.pixelsPerLine)

            // Then the line at the end
            g.fillRect(0, eY, eX, config.pixelsPerLine)

            if (eY + config.pixelsPerLine != sY) {
                // And if there is anything in between, fill it in
                g.fillRect(0, sY + config.pixelsPerLine, width, eY - sY - config.pixelsPerLine)
            }
        }
    }

    private fun paintSelections(g: Graphics2D) {
       paintSelection(g, editor.selectionModel.selectionStart, editor.selectionModel.selectionEnd)

        for ((index, start) in editor.selectionModel.blockSelectionStarts.withIndex()) {
            paintSelection(g, start, editor.selectionModel.blockSelectionEnds[index])
        }
    }

    override fun visibleAreaChanged(visibleAreaEvent: VisibleAreaEvent) {
        // TODO pending http://youtrack.jetbrains.com/issue/IDEABKL-1141 - once fixed this should be a listener
        var currentFoldCount = 0
        for (fold in editor.foldingModel.allFoldRegions) {
            if (!fold.isExpanded) {
                currentFoldCount++
            }
        }

        val visibleArea = editor.scrollingModel.visibleArea
        val factor = scrollstate.documentHeight.toDouble() / editor.contentComponent.height

        scrollstate.setViewportArea((factor * visibleArea.y).toInt(), (factor * visibleArea.height).toInt())
        scrollstate.setVisibleHeight(height)

        if (currentFoldCount != lastFoldCount) {
            updateImage()
        }

        lastFoldCount = currentFoldCount

        updateSize()
        repaint()
    }

    fun onClose() {
        container.removeComponentListener(componentListener)
        editor.document.removeDocumentListener(documentListener)
        editor.selectionModel.removeSelectionListener(selectionListener)
        remove(scrollbar)

        mapRef.clear()
    }
}
