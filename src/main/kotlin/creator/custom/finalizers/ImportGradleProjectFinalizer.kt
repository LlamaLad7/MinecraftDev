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

package com.demonwav.mcdev.creator.custom.finalizers

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.service.project.open.canLinkAndRefreshGradleProject
import org.jetbrains.plugins.gradle.service.project.open.linkAndSyncGradleProject

class ImportGradleProjectFinalizer : CreatorFinalizer {

    override suspend fun execute(
        context: WizardContext,
        project: Project,
        properties: Map<String, Any>,
        templateProperties: Map<String, Any?>
    ) = coroutineScope {
        val projectDir = context.projectFileDirectory
        val canLink = canLinkAndRefreshGradleProject(projectDir, project, showValidationDialog = false)
        thisLogger().info("canLink = $canLink projectDir = $projectDir")

        if (canLink) {
            val link = async {
                linkAndSyncGradleProject(project, projectDir)
            }

            runCatching {
                // Try to open the tool window after starting the sync.
                // Having the tool window open will provide better context to the user about what's going on.
                // The Build tool window isn't registered until a build is running, so we can't just open the tool window
                // like normal here, we have to wait until after the build starts.
                val manager = ToolWindowManager.getInstance(project)
                for (i in 0 until 10) {
                    delay(250)
                    manager.getToolWindow("Build")?.let {
                        withContext(Dispatchers.EDT) {
                            it.show()
                        }
                        break
                    }
                }
            }

            link.await()

            thisLogger().info("Linking done")
        }
    }
}
