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

import com.demonwav.mcdev.platform.mixin.expression.MEExpressionMatchUtil
import com.demonwav.mcdev.util.constantStringValue
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiModifierList
import com.intellij.psi.SmartPointerManager
import com.llamalad7.mixinextras.expression.impl.point.ExpressionContext
import com.mxgraph.layout.hierarchical.mxHierarchicalLayout
import com.mxgraph.model.mxCell
import com.mxgraph.swing.mxGraphComponent
import com.mxgraph.util.mxEvent
import com.mxgraph.util.mxRectangle
import com.mxgraph.view.mxGraph
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.SortedMap
import java.util.concurrent.Callable
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.Icon
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

class FlowDiagram(
    private val comp: mxGraphComponent,
    private val flowGraph: FlowGraph,
    private val clazz: ClassNode,
    val method: MethodNode,
    val panel: JPanel,
    val callbacks: FlowDiagramCallbacks,
) {
    companion object {
        suspend fun create(project: Project, clazz: ClassNode, method: MethodNode): FlowDiagram? {
            val flowGraph = FlowGraph.parse(project, clazz, method) ?: return null
            return buildDiagram(flowGraph, clazz, method)
        }
    }

    var matchExpression: ((jump: Boolean) -> Unit) = {}
        private set
    var jumpToExpression: () -> Unit = {}
        private set

    fun populateMatchStatuses(
        module: Module,
        currentStringLit: PsiLiteralExpression,
        currentModifierList: PsiModifierList
    ) {
        val stringRef = SmartPointerManager.getInstance(module.project).createSmartPsiElementPointer(currentStringLit)
        val modifierListRef =
            SmartPointerManager.getInstance(module.project).createSmartPsiElementPointer(currentModifierList)
        this.matchExpression = { jump ->
            val oldHighlightRoot = flowGraph.highlightRoot
            callbacks.setMatchToolbarVisible(false)
            flowGraph.resetMatches()
            ReadAction.nonBlocking(Callable<String?> run@{
                val stringLit = stringRef.element ?: return@run null
                val modifierList = modifierListRef.element ?: return@run null
                val expression = stringLit.constantStringValue?.let(MEExpressionMatchUtil::createExpression)
                    ?: return@run null
                val pool = MEExpressionMatchUtil.createIdentifierPoolFactory(module, clazz, modifierList)(method)
                for ((virtualInsn, root) in flowGraph.flowMap) {
                    val node = flowGraph.allNodes.getValue(root)
                    MEExpressionMatchUtil.findMatchingInstructions(
                        clazz, method, pool, flowGraph.flowMap, expression, listOf(virtualInsn),
                        ExpressionContext.Type.MODIFY_EXPRESSION_VALUE, // most permissive
                        false,
                        node::reportMatchStatus,
                        node::reportPartialMatch
                    ) {}
                }
                flowGraph.markHasMatchData()
                flowGraph.highlightMatches(oldHighlightRoot, false)
                StringUtil.escapeStringCharacters(expression.src.toString())
            })
                .finishOnUiThread(ModalityState.nonModal()) { exprText ->
                    exprText ?: return@finishOnUiThread
                    if (jump) {
                        showBestNode()
                    }
                    comp.refresh()
                    callbacks.setExprText(exprText)
                    callbacks.setMatchToolbarVisible(true)
                }
                .submit(ApplicationManager.getApplication()::executeOnPooledThread)
        }
        this.jumpToExpression = {
            ReadAction.run<Nothing> {
                val target = stringRef.element
                if (target is Navigatable && target.isValid && target.canNavigate()) {
                    target.navigate(true)
                }
            }
        }
        matchExpression(true)
    }

    private fun showBestNode() {
        val bestNode = flowGraph.allNodes.values.maxBy { it.matchScore }
        val bestCell = comp.graph.getChildVertices(comp.graph.defaultParent).asSequence()
            .map { it as mxCell }
            .find { it.value === bestNode }
            ?: return
        flowGraph.highlightMatches(bestNode, false)
        comp.scrollCellToVisible(bestCell, true)
    }

    fun clearExpression() {
        callbacks.setMatchToolbarVisible(false)
        flowGraph.resetMatches()
        comp.refresh()
        matchExpression = {}
        jumpToExpression = {}
    }
}

class FlowDiagramCallbacks(
    val scrollToLine: (Int) -> Unit,
    val setMatchToolbarVisible: (Boolean) -> Unit,
    val setExprText: (String) -> Unit
)

private class FlowDiagramRef {
    lateinit var diagram: FlowDiagram
        private set

    fun bind(newDiagram: FlowDiagram) {
        diagram = newDiagram
    }
}

private suspend fun buildDiagram(flowGraph: FlowGraph, clazz: ClassNode, method: MethodNode): FlowDiagram {
    val diagramRef = FlowDiagramRef()
    val graph = MxFlowGraph(flowGraph)
    setupStyles(graph)
    val groupedCells = addGraphContent(graph, flowGraph)
    val lineNumberNodes = sortedMapOf<Int, mxCell>()
    val calculateBounds = layOutGraph(graph, groupedCells, lineNumberNodes)

    val panel: JPanel
    val (comp, callbacks) = withContext(Dispatchers.EDT) {
        panel = JPanel(BorderLayout())
        displayGraphComponent(diagramRef, flowGraph, graph, panel, calculateBounds, lineNumberNodes)
    }
    return FlowDiagram(comp, flowGraph, clazz, method, panel, callbacks).also(diagramRef::bind)
}

private fun displayGraphComponent(
    diagramRef: FlowDiagramRef,
    flowGraph: FlowGraph,
    graph: mxGraph,
    panel: JPanel,
    calculateBounds: () -> Dimension,
    lineNumberNodes: SortedMap<Int, mxCell>
): Pair<mxGraphComponent, FlowDiagramCallbacks> {
    val comp = mxGraphComponent(graph)
    fun fixBounds() {
        comp.graphControl.preferredSize = calculateBounds()
    }

    graph.view.addListener(mxEvent.SCALE_AND_TRANSLATE) { _, _ ->
        fixBounds()
    }
    fixBounds()
    configureGraphComponent(comp, flowGraph)

    val (matchToolbar, setExprText) = createToolbars(diagramRef, comp, panel, ::fixBounds)
    panel.add(comp, BorderLayout.CENTER)

    return comp to FlowDiagramCallbacks(
        scrollToLine = { lineNumber ->
            lineNumberNodes.tailMap(lineNumber).firstEntry()?.let { (_, node) ->
                scrollCellToVisible(comp, node)
            }
        },
        setMatchToolbarVisible = { visible ->
            matchToolbar.isVisible = visible
        },
        setExprText = setExprText,
    )
}

private fun makeButton(icon: Icon, tooltip: String, action: () -> Unit): JButton =
    JButton(icon).apply {
        toolTipText = tooltip
        preferredSize = Dimension(32, 32)
        addActionListener {
            action()
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

private fun createToolbars(
    diagramRef: FlowDiagramRef,
    comp: mxGraphComponent,
    panel: JPanel,
    fixBounds: () -> Unit
): Pair<JToolBar, (String) -> Unit> {
    val container = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    container.add(createViewToolbar(comp, fixBounds))
    val (matchToolbar, setExprText) = createMatchToolbar(diagramRef)
    container.add(matchToolbar)

    panel.add(container, BorderLayout.NORTH)
    return matchToolbar to setExprText
}

private fun createViewToolbar(comp: mxGraphComponent, fixBounds: () -> Unit): JToolBar {
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

private fun createMatchToolbar(diagramRef: FlowDiagramRef): Pair<JToolBar, (String) -> Unit> {
    val helpLabel = JLabel("Showing matches for:").apply {
        border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
    }

    val exprText = JLabel(" ").apply {
        font = DiagramStyles.CURRENT_EDITOR_FONT
        border = BorderFactory.createEmptyBorder(0, 15, 0, 5)
        this.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    diagramRef.diagram.jumpToExpression()
                }
            }
        })
    }

    val refreshButton = makeButton(AllIcons.Actions.Refresh, "Re-match Expression") {
        diagramRef.diagram.matchExpression(false)
    }
    val closeButton = makeButton(AllIcons.Actions.CloseDarkGrey, "Clear Match Data") {
        diagramRef.diagram.clearExpression()
    }
    val buttonPanel = JPanel().apply {
        layout = FlowLayout(FlowLayout.RIGHT, 3, 3)
        isOpaque = false
        add(refreshButton)
        add(closeButton)
    }

    return JToolBar().apply {
        isVisible = false
        isFloatable = false
        layout = BorderLayout()
        add(helpLabel, BorderLayout.WEST)
        add(exprText, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.EAST)
    } to {
        exprText.text = it
        exprText.toolTipText = it
    }
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
                    val flow = (cell as mxCell).value as? FlowNode ?: continue
                    val texts = listOf(
                        graph.convertValueToString(cell),
                        graph.getToolTipForCell(cell),
                    )

                    if (searchText.isNotEmpty() && texts.any { searchText in it.lowercase() }) {
                        flow.searchHighlight = true
                        if (!scrolled) {
                            comp.scrollCellToVisible(cell, true)
                            comp.zoomTo(1.2, true)
                            graph.selectionCell = cell
                            scrolled = true
                        }
                    } else {
                        flow.searchHighlight = false
                    }
                }
            }
            comp.refresh()
            fixBounds()
        }
    })
    return searchField
}

private class MxFlowGraph(private val flowGraph: FlowGraph) : mxGraph() {
    override fun getToolTipForCell(cell: Any?): String? {
        val flow = (cell as? mxCell)?.value as? FlowNode ?: return super.getToolTipForCell(cell)
        if (!flowGraph.shouldShowTooltips()) {
            return null
        }
        val lines = mutableListOf<String>()
        flow.currentMatchResult?.let { match ->
            lines += match.toString(
                prefix = "`<span style='font-family: ${DiagramStyles.CURRENT_EDITOR_FONT};'>",
                suffix = "</span>`",
                transform = StringUtil::escapeXmlEntities
            )
        }
        lines += StringUtil.escapeXmlEntities(flow.longText).replace("\n", "<br>")
        return lines.joinToString(
            prefix = "<html>",
            separator = "<br><br>",
            postfix = "</html>"
        )
    }

    override fun convertValueToString(cell: Any?): String {
        val flow = (cell as? mxCell)?.value as? FlowNode ?: return super.convertValueToString(cell)
        return flow.shortText
    }

    override fun getCellStyle(cell: Any?): MutableMap<String, Any> {
        val result = super.getCellStyle(cell).toMutableMap()
        val flow = (cell as? mxCell)?.value as? FlowNode ?: return result
        when (flow.currentMatchResult?.status) {
            FlowMatchStatus.IGNORED -> result += DiagramStyles.IGNORED
            FlowMatchStatus.FAIL -> result += DiagramStyles.FAILED
            FlowMatchStatus.PARTIAL -> result += DiagramStyles.PARTIAL_MATCH
            FlowMatchStatus.SUCCESS -> result += DiagramStyles.SUCCESS
            null -> {}
        }
        if (flow.searchHighlight) {
            result += DiagramStyles.SEARCH_HIGHLIGHT
        }
        return result
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
    val stylesheet = graph.stylesheet
    stylesheet.defaultVertexStyle.putAll(DiagramStyles.DEFAULT_NODE)
    stylesheet.defaultEdgeStyle.putAll(DiagramStyles.DEFAULT_EDGE)
    stylesheet.putCellStyle(LINE_NUMBER_STYLE, DiagramStyles.LINE_NUMBER)
}

private fun configureGraphComponent(comp: mxGraphComponent, flowGraph: FlowGraph) {
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

    configureMouseListeners(comp, flowGraph)
}

private fun configureMouseListeners(comp: mxGraphComponent, flowGraph: FlowGraph) {
    fun highlight(e: MouseEvent, soft: Boolean) {
        val node = (comp.getCellAt(e.x, e.y) as mxCell?)?.value as? FlowNode
        flowGraph.highlightMatches(node, soft)
        comp.refresh()
        e.consume()
    }

    comp.graphControl.addMouseListener(object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            highlight(e, false)
        }
    })
    comp.graphControl.addMouseMotionListener(object : MouseAdapter() {
        override fun mouseMoved(e: MouseEvent) {
            highlight(e, true)
        }
    })
}

private inline fun <T> mxGraph.update(routine: () -> T): T {
    model.beginUpdate()
    try {
        return routine()
    } finally {
        model.endUpdate()
    }
}
