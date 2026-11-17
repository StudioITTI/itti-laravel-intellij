package com.studioitti.laravel.contrib

import com.studioitti.laravel.model.Domains
import com.studioitti.laravel.model.Models
import com.studioitti.laravel.support.VueSlots
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.jetbrains.php.lang.psi.elements.PhpClass
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

private fun marker(
	at: PsiElement, targets: List<PsiElement>, tooltip: String,
	result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
) {
	val distinct = targets.distinct()
	if (distinct.isEmpty()) return
	result.add(
		NavigationGutterIconBuilder.create(AllIcons.Nodes.Related)
			.setTargets(distinct)
			.setTooltipText(tooltip)
			.setPopupTitle(tooltip)
			.createLineMarkerInfo(at),
	)
}

/** Everything belonging to one model's domain, model first. */
private fun domainOf(model: PhpClass): List<PsiElement> {
	val controllers = Domains.controllersOf(model)
	return listOf(model) + Domains.schemaFiles(model) + controllers +
		controllers.flatMap { Domains.pagesOf(it) } + controllers.mapNotNull { Domains.routeOf(it, model) }
}

/** On a model or CRUD controller's name: its schema YAML, controller(s), Vue pages and route line. */
class PhpDomainLineMarker : RelatedItemLineMarkerProvider() {
	override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
		if (element.firstChild != null) return
		val cls = element.parent as? PhpClass ?: return
		if (cls.nameIdentifier != element) return
		val model = when {
			Domains.isController(cls) -> Domains.modelOf(cls)
			Domains.schemaFiles(cls).isNotEmpty() -> cls
			else -> null
		} ?: return
		marker(element, domainOf(model).filter { it != cls }, "Domain of ${model.name}", result)
	}
}

/** On the first key of a schema YAML: the model it describes and the rest of its domain. */
class YamlDomainLineMarker : RelatedItemLineMarkerProvider() {
	override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
		if (element.firstChild != null) return
		val kv = element.parent as? YAMLKeyValue ?: return
		if (kv.key != element) return
		val file = kv.containingFile as? YAMLFile ?: return
		if (file.name !in Domains.SCHEMA_FILES) return
		val top = (kv.parent as? YAMLMapping)?.takeIf { it.parent is YAMLDocument } ?: return
		if (top.keyValues.firstOrNull() != kv) return
		val model = Models.forSchemaFile(file) ?: return
		marker(element, domainOf(model).filter { it != file }, "Domain of ${model.name}", result)
	}
}

/** On `<template #name>` in an admin page: the schema entries (`slot`, `emptySlot`, `cells`) that use it. */
class VueSlotLineMarker : RelatedItemLineMarkerProvider() {
	override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
		if (element.firstChild != null) return
		val attribute = element.parent as? XmlAttribute ?: return
		if (attribute.nameElement != element) return
		val name = VueSlots.slotAt(element) ?: return
		val page = element.containingFile?.originalFile?.virtualFile ?: return
		if (!page.path.contains("/resources/js/Pages/Admin/")) return

		val controllers = Domains.controllers(element)
			.filter { ctrl ->Domains.pagesOf(ctrl).any { it.virtualFile == page } }
		val yamls: List<PsiFile> = controllers.mapNotNull { Domains.modelOf(it) }.distinct().flatMap { Domains.schemaFiles(it) }
		val uses = yamls.flatMap { PsiTreeUtil.findChildrenOfType(it, YAMLScalar::class.java) }.filter { scalar ->
			val kv = scalar.parent as? YAMLKeyValue ?: return@filter false
			val usesSlot = kv.keyText == "slot" || kv.keyText == "emptySlot" ||
				((kv.parent as? YAMLMapping)?.parent as? YAMLKeyValue)?.keyText == "cells"
			usesSlot && scalar.textValue == name
		}
		marker(element, uses, "Schema uses of slot '$name'", result)
	}
}
