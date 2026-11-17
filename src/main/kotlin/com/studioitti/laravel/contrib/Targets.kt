package com.studioitti.laravel.contrib

import com.studioitti.laravel.lang.LangKeys
import com.studioitti.laravel.lang.LangTarget
import com.studioitti.laravel.model.Domains
import com.studioitti.laravel.model.Models
import com.studioitti.laravel.support.LabelledTarget
import com.studioitti.laravel.support.LaravelProject
import com.studioitti.laravel.support.MdiIcons
import com.studioitti.laravel.support.PhpArrays
import com.studioitti.laravel.support.Routes
import com.studioitti.laravel.support.Views
import com.studioitti.laravel.support.VueSlots
import com.studioitti.laravel.support.relativeTo
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.PhpClass
import org.jetbrains.yaml.psi.YAMLMapping

/** Builders for each kind of value phase 2 links: one reference over the whole value unless noted. */
object Targets {
	private fun one(ref: PsiReference) = arrayOf(ref)

	private fun lookup(name: String, type: String? = null, icon: javax.swing.Icon? = null): LookupElement =
		LookupElementBuilder.create(name).withTypeText(type).withIcon(icon)

	/** A route name: `navigation.php` `route`. */
	fun route(element: PsiElement, value: String, range: TextRange) = one(TargetReference(
		element, range,
		{ listOfNotNull(Routes.index(element).route(value)) },
		{ Routes.index(element).routes.keys.map { lookup(it, "route", AllIcons.Nodes.Related) } },
		"Unknown route '$value'",
	))

	/** A route pattern: `navigation.php` `active`. `x.*` points at the resource or the routes it covers. */
	fun routePattern(element: PsiElement, value: String, range: TextRange): Array<PsiReference> {
		if (!value.endsWith(".*")) return route(element, value, range)
		val prefix = value.removeSuffix(".*")
		return one(TargetReference(
			element, TextRange(range.startOffset, range.startOffset + prefix.length),
			{
				val index = Routes.index(element)
				listOfNotNull(index.resources[prefix] ?: index.routes.entries.firstOrNull { it.key.startsWith("$prefix.") }?.value)
			},
			{ Routes.index(element).resources.keys.map { lookup(it, "resource", AllIcons.Nodes.Related) } },
			"No route matches '$value'",
		))
	}

	/** An `itti_resource_route()` name: `list:` in a form layout. Its route line, controller and the controller's schemas. */
	fun resource(element: PsiElement, value: String, range: TextRange) = one(TargetReference(
		element, range,
		{
			val literal = Routes.index(element).resources[value]
			listOfNotNull(literal) + (literal?.let(::resourceDomain) ?: emptyList())
		},
		{ Routes.index(element).resources.keys.map { lookup(it, "resource", AllIcons.Nodes.Related) } },
		"Unknown resource '$value' (no itti_resource_route)",
	))

	/** The controller named in `itti_resource_route('x', XCtrl::class)`, and its model's schema YAML. */
	private fun resourceDomain(literal: PsiElement): List<PsiElement> {
		val call = literal.parent?.parent as? com.jetbrains.php.lang.psi.elements.FunctionReference ?: return emptyList()
		val ref = (call.parameters.getOrNull(1) as? com.jetbrains.php.lang.psi.elements.ClassConstantReference)?.classReference
		val controller = (ref as? com.jetbrains.php.lang.psi.elements.ClassReference)?.resolve() as? PhpClass ?: return emptyList()
		return listOf(controller) + (Domains.modelOf(controller)?.let { Domains.schemaFiles(it) } ?: emptyList())
	}

	/** `repeater:` in a form layout: the field must be declared in ModelSchema with an `array-item`. */
	fun repeaterCheck(element: PsiElement, model: PhpClass, value: String, range: TextRange) = ProblemCheck(element, range) {
		val declared = Models.schemaFields(model)?.getKeyValueByKey(value)?.value as? YAMLMapping
		when {
			declared?.getKeyValueByKey("array-item") != null -> emptyList()
			declared == null && value !in Models.fields(model) -> emptyList() // unknown: the field path says so
			else -> listOf("'$value' has no array-item in ModelSchema.yaml, so it cannot be a repeater")
		}
	}

	/** Not a navigation target of its own — carries warnings for the annotator. */
	class ProblemCheck(element: PsiElement, range: TextRange, val problems: () -> List<String>) :
		com.intellij.psi.PsiReferenceBase<PsiElement>(element, range, true) {
		override fun resolve(): PsiElement? = null
	}

	/** A Blade view by dotted name. */
	fun view(element: PsiElement, value: String, range: TextRange, prefix: String = "") = one(TargetReference(
		element, range,
		{ listOfNotNull(Views.index(element)[prefix + value]) },
		{
			Views.index(element).keys.filter { it.startsWith(prefix) }
				.map { lookup(it.removePrefix(prefix), "view", AllIcons.FileTypes.Html) }
		},
		if (value.contains("::")) null else "View '${prefix + value}' not found",
	))

	/** A rich-text preset: `html-preset`. */
	fun htmlPreset(element: PsiElement, value: String, range: TextRange) = one(TargetReference(
		element, range,
		{ listOfNotNull(presets(element)?.let { PhpArrays.entry(it, value) }?.let { PhpArrays.unwrap(it.key) }) },
		{ presets(element)?.let { PhpArrays.entries(it).map { (k, _) -> lookup(k, "preset", AllIcons.Nodes.Property) } } ?: emptyList() },
		"Unknown rich-text preset '$value' (config/rich-text.php)",
	))

	private fun presets(element: PsiElement) = PhpArrays.configValue(element, "rich-text.presets") as? ArrayCreationExpression

	/** `mdi-…`: completion with previews, the glyph in the gutter, Ctrl+click to the icon's entry in @mdi/font. */
	fun icon(element: PsiElement, value: String, range: TextRange): Array<PsiReference> {
		val name = value.removePrefix("mdi-")
		return one(TargetReference(
			element, range,
			{
				val index = MdiIcons.index(element)
				listOfNotNull(index?.offsets?.get(name)?.let { index.variables.findElementAt(it) })
			},
			{
				val index = MdiIcons.index(element) ?: return@TargetReference emptyList()
				index.codepoints.keys.map { lookup("mdi-$it", null, MdiIcons.icon(index, it)) }
			},
			if (MdiIcons.index(element) == null) null else "Unknown icon '$value'",
			gutterIcon = { MdiIcons.index(element)?.let { MdiIcons.icon(it, value) } },
		))
	}

	/** A slot name: `slot` / `emptySlot` / a `cells` value, filled by `<template #name>` in the domain's pages or the components they import. */
	fun slot(element: PsiElement, model: PhpClass?, value: String, range: TextRange): Array<PsiReference> {
		fun candidates(): List<VueSlots.Slot> {
			val index = VueSlots.index(element)
			val pages = model?.let { Domains.pagesOfModel(it).map { p -> p.virtualFile } }.orEmpty()
			val files = index.reachable(pages)
			return index.all.filter { it.file.virtualFile in files }.ifEmpty { index.all }
		}
		return one(TargetReference(
			element, range,
			{
				candidates().filter { it.name == value }.map { slot ->
					val location = slot.file.virtualFile?.relativeTo(slot.element)
					LabelledTarget(slot.element, "#${slot.name}", location, AllIcons.Nodes.Template)
				}
			},
			{ candidates().map { lookup(it.name, it.file.name, AllIcons.Nodes.Template) }.distinctBy { it.lookupString } },
			"No <template #$value> in this domain's pages",
		))
	}

	/** `tabTitle`: a field of the repeater's items, per ModelSchema `fields.<repeater>.array-item.fields`. */
	fun repeaterItemField(element: PsiElement, model: PhpClass?, repeater: String?, value: String, range: TextRange): Array<PsiReference> {
		fun fields(): YAMLMapping? {
			val field = Models.schemaFields(model ?: return null)?.getKeyValueByKey(repeater ?: return null)?.value as? YAMLMapping
			val item = field?.getKeyValueByKey("array-item")?.value as? YAMLMapping
			return item?.getKeyValueByKey("fields")?.value as? YAMLMapping
		}
		return one(TargetReference(
			element, range,
			{ listOfNotNull(fields()?.getKeyValueByKey(value)) },
			{ fields()?.keyValues?.map { lookup(it.keyText, "item field", AllIcons.Nodes.Field) } ?: emptyList() },
			if (fields() == null) null else "Repeater '$repeater' items have no field '$value'",
		))
	}

	/** A key of an array literal (a registry entry's `fields`, the mail catalogue, …). */
	fun arrayKey(
		element: PsiElement, value: String, range: TextRange, array: () -> ArrayCreationExpression?,
		type: String, unknown: String?, severity: HighlightSeverity = HighlightSeverity.WARNING,
	) = one(TargetReference(
		element, range,
		{ listOfNotNull(array()?.let { PhpArrays.entry(it, value) }?.let { PhpArrays.unwrap(it.key) }) },
		{ array()?.let { PhpArrays.entries(it).map { (k, _) -> lookup(k, type, AllIcons.Nodes.Property) } } ?: emptyList() },
		unknown, severity,
	))

	fun mailTemplates(element: PsiElement) =
		LaravelProject.psiFile(element, "config/mail-templates.php")?.let { PhpArrays.returnedArray(it) }

	fun mailPlaceholders(element: PsiElement, code: String): ArrayCreationExpression? =
		mailTemplates(element)?.let { PhpArrays.entry(it, code) }
			?.let { PhpArrays.value(it) as? ArrayCreationExpression }
			?.let { PhpArrays.entry(it, "placeholders") }
			?.let { PhpArrays.value(it) as? ArrayCreationExpression }

	/**
	 * Where a permission is listed and translated. One set: `database/permissions.php`, `admin.permissions.<perm>`.
	 * Per scope (`config('permissions.output')`): that scope's file, `admin.permissions.<scope>.<perm>`.
	 */
	class PermissionSet(val mapPath: String?, val langPrefix: List<String>)

	fun permissionSet(element: PsiElement, scope: String?): PermissionSet {
		val output = PhpArrays.configValue(element, "permissions.output") as? ArrayCreationExpression
			?: return PermissionSet("database/permissions.php", listOf("admin", "permissions"))
		val resolved = scope
			?: (PhpArrays.configValue(element, "permissions.default_scope") as? com.jetbrains.php.lang.psi.elements.StringLiteralExpression)?.contents
			?: return PermissionSet(null, listOf("admin", "permissions"))
		val path = PhpArrays.entry(output, resolved)?.let { PhpArrays.value(it) }?.let(::projectPath)
		return PermissionSet(path, listOf("admin", "permissions", resolved))
	}

	/** `database_path('x')` / `base_path('x')` / `'x'` as a path relative to the project root. */
	private fun projectPath(e: PsiElement): String? {
		if (e is com.jetbrains.php.lang.psi.elements.StringLiteralExpression) return e.contents
		val call = e as? com.jetbrains.php.lang.psi.elements.FunctionReference ?: return null
		val arg = (call.parameters.firstOrNull() as? com.jetbrains.php.lang.psi.elements.StringLiteralExpression)?.contents?.removePrefix("/") ?: return null
		val base = mapOf("base_path" to "", "database_path" to "database/", "config_path" to "config/", "storage_path" to "storage/")[call.name] ?: return null
		return base + arg
	}

	/** `#[Restricted('perm')]`: the generated map entry and its translations. */
	fun permission(element: PsiElement, permission: String, scope: String?, range: TextRange): Array<PsiReference> {
		val set = permissionSet(element, scope)
		fun map() = set.mapPath?.let { LaravelProject.psiFile(element, it) }?.let { PhpArrays.returnedArray(it) }
		val langKey = set.langPrefix + permission
		return arrayOf(
			TargetReference(
				element, range,
				{
					listOfNotNull(map()?.let { PhpArrays.entry(it, permission) }?.let { PhpArrays.unwrap(it.key) }) +
						LangKeys.resolve(element, langKey).map { LangTarget(it) }
				},
				unknown = null,
			),
			PermissionCheck(element, range, permission, set.mapPath, langKey, ::map),
		)
	}

	/** Not a navigation target of its own — carries the permission warnings for the annotator. */
	class PermissionCheck(
		element: PsiElement, range: TextRange, val permission: String,
		private val mapPath: String?, private val langKey: List<String>,
		private val map: () -> ArrayCreationExpression?,
	) : com.intellij.psi.PsiReferenceBase<PsiElement>(element, range, true) {
		override fun resolve(): PsiElement? = null

		fun problems(): List<String> {
			val out = ArrayList<String>()
			val map = map()
			if (map != null && PhpArrays.entry(map, permission) == null) {
				out += "'$permission' is not in $mapPath — run `php artisan permissions:build --preserve`"
			}
			val found = LangKeys.resolve(element, langKey).map { it.locale }.toSet()
			val missing = LangKeys.locales(element).filter { it !in found }
			if (missing.isNotEmpty()) out += "No ${langKey.joinToString(".")} translation in: ${missing.joinToString(", ")}"
			return out
		}
	}

	/** The hash element directly holding this value, if any (`'key' => <value>`). */
	fun ownerHash(value: PsiElement): ArrayHashElement? {
		val hash = value.parent?.parent as? ArrayHashElement ?: value.parent as? ArrayHashElement ?: return null
		return hash.takeIf { PhpArrays.value(it) == value }
	}

	fun relative(element: PsiElement, file: com.intellij.psi.PsiFile) = file.virtualFile?.relativeTo(element) ?: file.name
}
