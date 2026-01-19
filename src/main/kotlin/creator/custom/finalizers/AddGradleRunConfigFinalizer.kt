package com.demonwav.mcdev.creator.custom.finalizers

import com.intellij.execution.RunManager
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration

class AddGradleRunConfigFinalizer : AddRunConfigFinalizer {

    override val executablesName: String = "tasks"

    override suspend fun execute(
        context: WizardContext,
        project: Project,
        properties: Map<String, Any>,
        templateProperties: Map<String, Any?>
    ) {
        val tasks = properties.executables
        val projectDir = context.projectFileDirectory

        val gradleType = GradleExternalTaskConfigurationType.getInstance()

        val runManager = RunManager.getInstance(project)
        val runConfigName = properties["name"] as String

        val runConfiguration = GradleRunConfiguration(project, gradleType.factory, runConfigName)

        runConfiguration.settings.externalProjectPath = projectDir
        runConfiguration.settings.executionName = runConfigName
        runConfiguration.settings.taskNames = tasks

        val settings = runManager.createConfiguration(runConfiguration, gradleType.factory)
        settings.isActivateToolWindowBeforeRun = true
        settings.storeInLocalWorkspace()

        runManager.addConfiguration(settings)

        if (properties["select"] == true || runManager.selectedConfiguration == null) {
            runManager.selectedConfiguration = settings
        }
    }
}
