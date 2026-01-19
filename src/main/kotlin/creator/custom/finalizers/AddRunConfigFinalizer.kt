package com.demonwav.mcdev.creator.custom.finalizers

import com.demonwav.mcdev.creator.custom.TemplateValidationReporter

interface AddRunConfigFinalizer : CreatorFinalizer {

    val executablesName: String
    val Map<String, Any>.executables: List<String>
        @Suppress("UNCHECKED_CAST")
        get() = this[executablesName] as List<String>

    override fun validate(
        reporter: TemplateValidationReporter,
        properties: Map<String, Any>
    ) {
        @Suppress("UNCHECKED_CAST")
        val executables = properties[executablesName] as? List<String>
        if (executables == null) {
            reporter.warn("Missing list of '$executables' to execute")
        }

        @Suppress("UNCHECKED_CAST")
        val name = properties["name"] as? String
        if (name == null) {
            reporter.warn("Missing task name")
        }
    }
}
