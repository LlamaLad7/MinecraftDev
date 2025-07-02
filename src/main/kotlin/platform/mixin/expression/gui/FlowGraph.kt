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
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.llamalad7.mixinextras.expression.impl.flow.FlowValue
import com.llamalad7.mixinextras.expression.impl.flow.expansion.InsnExpander
import java.util.SortedSet
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodNode

class FlowNode(val flow: FlowValue, project: Project, clazz: ClassNode, method: MethodNode) {
    val inputs = (0..<flow.inputCount()).map { FlowNode(flow.getInput(it), project, clazz, method) }
    val shortText = flow.shortString(project, clazz, method)
    val longText = flow.longString()

    fun dfs(): Sequence<FlowNode> =
        sequenceOf(this) + inputs.asSequence().flatMap { it.dfs() }
}

class FlowGroup(val root: FlowNode, method: MethodNode) : Comparable<FlowGroup> {
    private val startIndex = root.dfs()
        .map { it.flow }
        .filterNot { it.isComplex }
        .minOf {
            method.instructions.indexOf(InsnExpander.getRepresentative(it))
        }

    val lineNumber =
        generateSequence(method.instructions.get(startIndex)) { it.previous }
            .filterIsInstance<LineNumberNode>()
            .firstOrNull()?.line ?: -1

    override fun compareTo(other: FlowGroup) = compareValuesBy(this, other, { it.lineNumber }, { it.startIndex })
}

class FlowGraph(val groups: SortedSet<FlowGroup>) {
    operator fun iterator() = groups.iterator()

    companion object {
        suspend fun parse(project: Project, clazz: ClassNode, method: MethodNode): FlowGraph? {
            val flows = readAction { MEExpressionMatchUtil.getFlowMap(project, clazz, method) }?.values ?: return null
            val groups = sortedSetOf<FlowGroup>()
            for (flow in flows) {
                if (!flow.isRoot) {
                    continue
                }
                @Suppress("UnstableApiUsage")
                checkCanceled()

                val node = FlowNode(flow, project, clazz, method)
                groups.add(FlowGroup(node, method))
            }
            return FlowGraph(groups)
        }
    }
}

private val FlowValue.isRoot get() = next.isEmpty()
