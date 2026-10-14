package de.inetsoftware.gradlesyncqueue

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel

class QueueWindowFactory : ToolWindowFactory, DumbAware {
    /** One list row; [gateId] is set for entries that can be cancelled in the gate. */
    private data class Row(val text: String, val gateId: String?) { override fun toString() = text }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = GradleQueueService.get(project)
        val gate = ImportGate.get(project)
        val model = DefaultListModel<Row>()
        val list = JBList(model).apply { emptyText.text = "No Gradle syncs yet" }
        val time = SimpleDateFormat("HH:mm:ss")

        val queueAll = JButton("Queue all linked Gradle builds")
        val cancel = JButton("Cancel / release selected")
        val clear = JButton("Clear finished")
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(queueAll); add(cancel); add(clear) }

        val panel = JPanel(BorderLayout())
        panel.add(buttons, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)

        fun update() {
            val owner = gate.owner()
            model.clear()
            gate.snapshot().forEach {
                val holder = if (it.id == owner) " (holds gate)" else ""
                val message = it.message?.let { m -> ": $m" } ?: ""
                model.addElement(Row("[IDEA] ${time.format(Date(it.since))}  ${it.state.label}$message$holder  —  ${it.path}",
                    it.id.takeUnless { _ -> it.state.terminal }))
            }
            service.snapshot().forEach { model.addElement(Row("[Requested] ${it.state}  —  ${it.path}", null)) }
            cancel.isEnabled = list.selectedValuesList.any { it.gateId != null }
        }

        list.addListSelectionListener { cancel.isEnabled = list.selectedValuesList.any { it.gateId != null } }
        queueAll.addActionListener { service.enqueue(service.linkedPaths()) }
        cancel.addActionListener { list.selectedValuesList.mapNotNull { it.gateId }.forEach(gate::cancel) }
        clear.addActionListener { gate.clearFinished(); service.clearFinished() }

        val content = ContentFactory.getInstance().createContent(panel, "Queue", false)
        val unsubscribeService = service.addListener { update() }
        val unsubscribeGate = gate.subscribe { update() }
        Disposer.register(content) { unsubscribeService(); unsubscribeGate() }
        update()
        toolWindow.contentManager.addContent(content)
    }
}
