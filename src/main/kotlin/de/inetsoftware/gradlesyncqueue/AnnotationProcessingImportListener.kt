package de.inetsoftware.gradlesyncqueue

import com.intellij.compiler.CompilerConfiguration
import com.intellij.compiler.CompilerConfigurationImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import org.jetbrains.jps.model.java.impl.compiler.ProcessorConfigProfileImpl
import org.jetbrains.plugins.gradle.model.data.AnnotationProcessingData
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File

/** Reconciles shared processor profiles from all cached builds, including after a single-build refresh. */
class AnnotationProcessingImportListener(private val project: Project) : ProjectDataImportListener {
    override fun onImportFinished(projectPath: String?) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val declarations = linkedMapOf<String, AnnotationProcessingProfiles.Spec>()
            val modules = ModuleManager.getInstance(project).modules.associateBy { it.name }
            for (info in ProjectDataManager.getInstance().getExternalProjectsData(project, GradleConstants.SYSTEM_ID)) {
                val structure = info.externalProjectStructure ?: continue
                for (node in ExternalSystemApiUtil.findAllRecursively(structure, AnnotationProcessingData.KEY)) {
                    val owner = node.parent?.data as? ModuleData ?: continue
                    val module = modules[owner.internalName] ?: continue
                    if (!ExternalSystemApiUtil.isExternalSystemAwareModule(GradleConstants.SYSTEM_ID, module)) continue
                    val data = node.data
                    val options = data.arguments.associate { argument ->
                        val option = argument.removePrefix("-A")
                        option.substringBefore('=') to option.substringAfter('=', "")
                    }
                    declarations[module.name] = AnnotationProcessingProfiles.Spec(data.path.joinToString(File.pathSeparator), options)
                }
            }
            WriteAction.run<RuntimeException> {
                val configuration = CompilerConfiguration.getInstance(project) as CompilerConfigurationImpl
                val profiles = configuration.moduleProcessorProfiles
                val repaired = AnnotationProcessingProfiles.reconcile(profiles, declarations)
                if (profiles != repaired || profiles.zip(repaired).any { (a, b) -> a.moduleNames != b.moduleNames }) {
                    configuration.setModuleProcessorProfiles(repaired)
                    LOG.info("Reconciled Gradle annotation processors for ${declarations.size} modules across linked builds")
                }
            }
        }, project.disposed)
    }

    companion object {
        private val LOG = logger<AnnotationProcessingImportListener>()
    }
}

/** Pure reconciliation: cached Gradle models are authoritative; manually named profiles retain their modules. */
internal object AnnotationProcessingProfiles {
    data class Spec(val path: String, val options: Map<String, String>)

    fun reconcile(
        existing: List<org.jetbrains.jps.model.java.compiler.ProcessorConfigProfile>,
        declarations: Map<String, Spec>,
    ): List<org.jetbrains.jps.model.java.compiler.ProcessorConfigProfile> {
        val manual = existing.filter { it.name != "Gradle Imported" }
        val manualModules = manual.flatMap { it.moduleNames }.toSet()
        val grouped = declarations.filterKeys { it !in manualModules }.entries.groupBy { it.value }
        return manual + grouped.map { (spec, modules) ->
            ProcessorConfigProfileImpl("Gradle Imported").apply {
                isEnabled = true
                isObtainProcessorsFromClasspath = false
                isOutputRelativeToContentRoot = true
                setProcessorPath(spec.path)
                spec.options.forEach { (key, value) -> setOption(key, value) }
                modules.forEach { addModuleName(it.key) }
            }
        }
    }
}
