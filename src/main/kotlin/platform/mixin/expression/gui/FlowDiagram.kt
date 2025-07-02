/*
 * Minecraft Development for IntelliJ
 *
 * https://mcdev.io/
 *
 * Copyright (C) 2025 minecraft-dev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, version 3.0 only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.demonwav.mcdev.platform.mixin.expression.gui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mxgraph.layout.hierarchical.mxHierarchicalLayout
import com.mxgraph.model.mxCell
import com.mxgraph.swing.mxGraphComponent
import com.mxgraph.util.mxConstants
import com.mxgraph.util.mxEvent
import com.mxgraph.util.mxRectangle
import com.mxgraph.view.mxGraph
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.util.SortedMap
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JToolBar
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

private const val OUTER_PADDING = 30.0
private const val INTER_GROUP_SPACING = 75
private const val INTRA_GROUP_SPACING = 75
private const val LINE_NUMBER_STYLE = "LINE_NUMBER"
private const val HIGHLIGHT_STYLE = "HIGHLIGHT"

class FlowDiagram(
    val method: MethodNode,
    val panel: JPanel,
    val scrollToLine: (Int) -> Unit,
) {
    companion object {
        suspend fun create(project: Project, clazz: ClassNode, method: MethodNode): FlowDiagram? {
            val flowGraph = FlowGraph.parse(project, clazz, method) ?: return null
            return buildPanel(flowGraph, method)
        }
    }
}

private suspend fun buildPanel(flowGraph: FlowGraph, method: MethodNode): FlowDiagram {
    val graph = MxFlowGraph()
    setupStyles(graph)
    val groupedCells = addGraphContent(graph, flowGraph)
    val lineNumberNodes = sortedMapOf<Int, mxCell>()
    val calculateBounds = layOutGraph(graph, groupedCells, lineNumberNodes)

    val panel: JPanel
    val scrollToLine = withContext(Dispatchers.EDT) {
        panel = JPanel(BorderLayout())
        displayGraphComponent(graph, panel, calculateBounds, lineNumberNodes)
    }
    return FlowDiagram(method, panel, scrollToLine)
}

private fun displayGraphComponent(
    graph: mxGraph,
    panel: JPanel,
    calculateBounds: () -> Dimension,
    lineNumberNodes: SortedMap<Int, mxCell>
): (Int) -> Unit {
    val comp = mxGraphComponent(graph)
    fun fixBounds() {
        comp.graphControl.preferredSize = calculateBounds()
    }

    graph.view.addListener(mxEvent.SCALE_AND_TRANSLATE) { _, _ ->
        fixBounds()
    }
    fixBounds()
    configureGraphComponent(comp)

    val toolbar = createToolbar(comp, ::fixBounds)
    panel.add(toolbar, BorderLayout.NORTH)
    panel.add(comp, BorderLayout.CENTER)

    return { lineNumber ->
        lineNumberNodes.tailMap(lineNumber).firstEntry()?.let { (_, node) ->
            scrollCellToVisible(comp, node)
        }
    }
}

private fun scrollCellToVisible(comp: mxGraphComponent, node: mxCell) {
    // Scrolls the cell to the top of the screen if possible
    val graph = comp.graph
    val state = graph.view.getState(node) ?: return
    val cellBounds = state.rectangle
    val viewRect = comp.viewport.viewRect
    val targetRect = Rectangle(
        cellBounds.x, cellBounds.y,
        1, viewRect.height
    )
    comp.graphControl.scrollRectToVisible(targetRect)
}

private fun createToolbar(comp: mxGraphComponent, fixBounds: () -> Unit): JToolBar {
    val toolbar = JToolBar()
    toolbar.isFloatable = false
    val zoomInButton = JButton("+")
    zoomInButton.toolTipText = "Zoom In"
    zoomInButton.addActionListener {
        comp.zoomIn()
    }
    val zoomOutButton = JButton("−")
    zoomOutButton.toolTipText = "Zoom Out"
    zoomOutButton.addActionListener {
        comp.zoomOut()
    }
    toolbar.add(zoomInButton)
    toolbar.add(zoomOutButton)
    toolbar.addSeparator(Dimension(20, 0))
    toolbar.add(JLabel("Search: "))
    toolbar.add(createSearchField(comp, fixBounds))
    return toolbar
}

private fun createSearchField(comp: mxGraphComponent, fixBounds: () -> Unit): JTextField {
    val graph = comp.graph
    val searchField = JTextField()
    searchField.document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = updateHighlight()

        override fun removeUpdate(e: DocumentEvent) = updateHighlight()

        override fun changedUpdate(e: DocumentEvent) = updateHighlight()

        private fun updateHighlight() {
            val searchText = searchField.text.lowercase()
            graph.update {
                val vertices = graph.getChildVertices(graph.defaultParent)
                var scrolled = false

                for (cell in vertices) {
                    cell as mxCell
                    if (cell.style == LINE_NUMBER_STYLE) {
                        continue
                    }
                    val texts = listOf(
                        graph.convertValueToString(cell),
                        graph.getToolTipForCell(cell),
                    )

                    if (searchText.isNotEmpty() && texts.any { searchText in it.lowercase() }) {
                        graph.setCellStyle(HIGHLIGHT_STYLE, arrayOf(cell))
                        if (!scrolled) {
                            comp.scrollCellToVisible(cell, true)
                            comp.zoomTo(1.2, true)
                            graph.selectionCell = cell
                            scrolled = true
                        }
                    } else {
                        graph.model.setStyle(cell, null)
                    }
                }
            }
            comp.refresh()
            fixBounds()
        }
    })
    return searchField
}

private class MxFlowGraph : mxGraph() {
    override fun getToolTipForCell(cell: Any?): String {
        val flow = (cell as? mxCell)?.value as? FlowNode ?: return super.getToolTipForCell(cell)
        return flow.longText
    }

    override fun convertValueToString(cell: Any?): String {
        val flow = (cell as? mxCell)?.value as? FlowNode ?: return super.convertValueToString(cell)
        return flow.shortText
    }
}

private suspend fun addGraphContent(
    graph: mxGraph,
    flowGraph: FlowGraph
): SortedMap<FlowGroup, List<mxCell>> {
    val groupedCells = sortedMapOf<FlowGroup, List<mxCell>>()
    graph.update {
        fun addFlow(flow: FlowNode, parent: mxCell?, out: (mxCell) -> Unit) {
            val node = graph.insertVertex(null, null, flow, 0.0, 0.0, 0.0, 0.0) as mxCell
            graph.updateCellSize(node, true)
            if (parent != null) {
                out(graph.insertEdge(null, null, null, node, parent) as mxCell)
            }
            for (input in flow.inputs) {
                addFlow(input, node, out)
            }
            out(node)
        }

        for (group in flowGraph) {
            @Suppress("UnstableApiUsage")
            checkCanceled()
            val cells = mutableListOf<mxCell>()
            addFlow(group.root, null, cells::add)
            groupedCells[group] = cells
        }
    }
    return groupedCells
}

private suspend fun layOutGraph(
    graph: mxGraph,
    groupedCells: SortedMap<FlowGroup, List<mxCell>>,
    lineNumberNodes: SortedMap<Int, mxCell>
): () -> Dimension {
    val layout = mxHierarchicalLayout(graph)
    var lastBounds = mxRectangle(0.0, 0.0, 0.0, 0.0)
    var maxX = 0.0
    var maxY = 0.0
    var lastLine: Int? = null
    for ((group, list) in groupedCells) {
        @Suppress("UnstableApiUsage")
        checkCanceled()

        val (targetLeft, targetTop) = if (group.lineNumber == lastLine) {
            (lastBounds.x + lastBounds.width + INTRA_GROUP_SPACING) to (lastBounds.y)
        } else {
            val label = graph.insertVertex(
                null, null,
                "Line ${group.lineNumber}:",
                OUTER_PADDING / 2,
                maxY + INTER_GROUP_SPACING / 2,
                0.0, 0.0,
                LINE_NUMBER_STYLE
            ) as mxCell
            lineNumberNodes[group.lineNumber] = label
            graph.updateCellSize(label, true)
            graph.moveCells(arrayOf(label), 0.0, -graph.view.getState(label).height / 2)
            (OUTER_PADDING) to (maxY + INTER_GROUP_SPACING)
        }
        layout.execute(graph.getDefaultParent(), list)
        val cells = list.toTypedArray()
        val bounds = graph.view.getBounds(cells)
        graph.moveCells(cells, -bounds.x + targetLeft, -bounds.y + targetTop)
        lastBounds = mxRectangle(targetLeft, targetTop, bounds.width, bounds.height)
        maxX = maxOf(maxX, lastBounds.x + lastBounds.width)
        maxY = maxOf(maxY, lastBounds.y + lastBounds.height)
        lastLine = group.lineNumber
    }

    return {
        Dimension(
            ((maxX + OUTER_PADDING) * graph.view.scale).toInt(),
            ((maxY + OUTER_PADDING) * graph.view.scale).toInt()
        )
    }
}

private fun setupStyles(graph: mxGraph) {
    val colorScheme = EditorColorsManager.getInstance().globalScheme
    graph.stylesheet.defaultVertexStyle.let {
        it[mxConstants.STYLE_FONTFAMILY] = colorScheme.getFont(EditorFontType.PLAIN).family
        it[mxConstants.STYLE_ROUNDED] = true
        it[mxConstants.STYLE_FILLCOLOR] = JBUI.CurrentTheme.Button.buttonColorStart().hexString
        it[mxConstants.STYLE_FONTCOLOR] = UIUtil.getLabelForeground().hexString
        it[mxConstants.STYLE_STROKECOLOR] = JBUI.CurrentTheme.Button.buttonOutlineColorStart(false).hexString
        it[mxConstants.STYLE_ALIGN] = mxConstants.ALIGN_CENTER
        it[mxConstants.STYLE_VERTICAL_ALIGN] = mxConstants.ALIGN_TOP
        it[mxConstants.STYLE_SHAPE] = mxConstants.SHAPE_LABEL
        it[mxConstants.STYLE_SPACING] = 5
        it[mxConstants.STYLE_SPACING_TOP] = 3
    }

    graph.stylesheet.defaultEdgeStyle.let {
        it[mxConstants.STYLE_STROKECOLOR] = UIUtil.getFocusedBorderColor().hexString
    }

    graph.stylesheet.putCellStyle(
        LINE_NUMBER_STYLE,
        mapOf(
            mxConstants.STYLE_FONTSIZE to "16",
            mxConstants.STYLE_STROKECOLOR to "none",
            mxConstants.STYLE_FILLCOLOR to "none",
        )
    )
    graph.stylesheet.putCellStyle(
        HIGHLIGHT_STYLE,
        mapOf(
            mxConstants.STYLE_STROKECOLOR to UIUtil.getFocusedBorderColor().hexString,
            mxConstants.STYLE_STROKEWIDTH to "2",
        )
    )
}

private fun configureGraphComponent(comp: mxGraphComponent) {
    val graph = comp.graph
    graph.isCellsSelectable = false
    graph.isCellsEditable = false
    comp.isConnectable = false
    comp.isPanning = true
    comp.setToolTips(true)
    comp.viewport.setOpaque(true)
    comp.viewport.setBackground(EditorColorsManager.getInstance().globalScheme.defaultBackground)

    comp.zoomAndCenter()
    comp.graphControl.isDoubleBuffered = false
    comp.graphControl.setOpaque(false)
    comp.verticalScrollBar.setUnitIncrement(16)
    comp.horizontalScrollBar.setUnitIncrement(16)
}

private val Color.hexString get() = "#%06X".format(rgb)

private inline fun <T> mxGraph.update(routine: () -> T): T {
    model.beginUpdate()
    try {
        return routine()
    } finally {
        model.endUpdate()
    }
}
