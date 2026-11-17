package com.studioitti.laravel.contrib

import com.studioitti.laravel.lang.LangKeys
import com.studioitti.laravel.model.FieldPath
import com.studioitti.laravel.model.Models
import com.studioitti.laravel.model.PathMode
import com.studioitti.laravel.support.MdiIcons
import com.studioitti.laravel.support.PhpArrays
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpReturn
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.elements.Variable
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/** Every reference this plugin puts on a string, whichever language it is in. */
object References {
	/** `labelKey`, `hintKey`, `titleKey`, `textKey`, `htmlKey`, … */
	private val LANG_KEY = Regex("^[a-z]+Key$")
	private val SCHEMA_FILES = setOf("ModelSchema.yaml", "ListSchema.yaml", "EditSchema.yaml")
	private val TRANSLATE_CALLS = setOf("t", "\$t", "tc", "\$tc", "te", "\$te", "trans", "trans_choice", "__")
	private val FILTER_PATH = Regex("^list\\.filters\\.[^.]+\\.\\[](\\.field)?$")
	private val REL_SETTING = Regex("^fields\\.[^.]+\\.rel\\.(title_field|value_field|(attributes|search-attributes|sort-attributes)\\.\\[])$")
	private val PARTIAL_DIRS = mapOf("cms-blocks.php" to "public.blocks.", "cms-popups.php" to "public.popups.")
	private val PLACEHOLDER = Regex("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}")
	private val MAIL_CLASSES = setOf("MailTemplateRenderer", "EmailTemplate")
	private val MAIL_METHODS = setOf("render", "renderPreview", "isEnabled", "forCode", "definition")

	fun of(element: PsiElement): Array<PsiReference> = when (element) {
		is YAMLScalar -> yaml(element)
		is JSLiteralExpression -> js(element)
		is StringLiteralExpression -> php(element)
		is XmlAttributeValue -> xml(element)
		else -> PsiReference.EMPTY_ARRAY
	}

	// ---------------------------------------------------------------- YAML

	private fun yaml(scalar: YAMLScalar): Array<PsiReference> {
		val range = ElementManipulators.getValueTextRange(scalar)
		val text = range.substring(scalar.text)
		val kv = scalar.parent as? YAMLKeyValue
		if (kv != null && LANG_KEY.matches(kv.keyText)) return LangKeys.references(scalar, text, range)
		if (MdiIcons.isName(text)) return Targets.icon(scalar, text, range)
		if (kv?.keyText == "html-preset") return Targets.htmlPreset(scalar, text, range)

		if (scalar.containingFile.originalFile.name !in SCHEMA_FILES) return PsiReference.EMPTY_ARRAY
		val path = yamlPath(scalar)
		val joined = path.joinToString(".")
		val model = Models.forSchemaFile(scalar.containingFile) ?: return PsiReference.EMPTY_ARRAY
		val inLayout = isInLayout(path)

		return when {
			joined in setOf("list.columns.[]", "list.columns.[].field", "list.search.[]", "list.totals.[].field")
				|| FILTER_PATH.matches(joined)
				|| isLayoutField(path) ->
				FieldPath.references(scalar, model, text, range, PathMode.FIELD)

			inLayout && (path.last() == "slot" || path.last() == "emptySlot" || path.getOrNull(path.size - 2) == "cells") ->
				Targets.slot(scalar, model, text, range)

			inLayout && path.last() == "list" -> Targets.resource(scalar, text, range)

			inLayout && path.last() == "tabTitle" -> {
				val repeater = ((scalar.parent as? YAMLKeyValue)?.parent as? YAMLMapping)?.getKeyValueByKey("repeater")?.valueText
				Targets.repeaterItemField(scalar, model, repeater, text, range)
			}

			joined == "order.field" || joined == "order.scope.[]" ->
				FieldPath.references(scalar, model, text, range, PathMode.FIELD)

			path.size == 4 && path[0] == "fields" && path[2] == "rel" && path[3] == "name" ->
				FieldPath.references(scalar, model, text, range, PathMode.RELATION)

			REL_SETTING.matches(joined) -> {
				val relName = generateSequence(scalar.parent) { it.parent }
					.filterIsInstance<YAMLKeyValue>()
					.firstOrNull { it.keyText == "rel" }
					?.let { (it.value as? YAMLMapping)?.getKeyValueByKey("name")?.valueText }
				val related = relName?.let { Models.relation(model, it) }?.let { Models.related(it, model) }
				FieldPath.references(scalar, related, text, range, PathMode.FIELD)
			}

			else -> PsiReference.EMPTY_ARRAY
		}
	}

	private fun isInLayout(path: List<String>) =
		path.size >= 3 && path[0] in setOf("edit", "create") && path[1] == "formLayout" && "props" !in path

	/**
	 * A bare item of a formLayout sequence, or a `field:` / `repeater:` value. Not inside a repeater's
	 * `item` (named relative to the item) nor `props` (passed through to Vuetify).
	 */
	private fun isLayoutField(path: List<String>): Boolean {
		if (!isInLayout(path) || "item" in path) return false
		return path.last() == "[]" || path.last() == "field" || path.last() == "repeater"
	}

	private fun yamlPath(element: PsiElement): List<String> {
		val out = ArrayList<String>()
		var cur: PsiElement? = element
		while (cur != null && cur !is com.intellij.psi.PsiFile) {
			when (cur) {
				is YAMLKeyValue -> out += cur.keyText
				is YAMLSequenceItem -> out += "[]"
			}
			cur = cur.parent
		}
		return out.reversed()
	}

	// ---------------------------------------------------------------- JS / Vue

	/** The first argument of `t('…')`, `$t('…')` and friends; any `'mdi-…'` string. */
	private fun js(literal: JSLiteralExpression): Array<PsiReference> {
		if (!literal.isQuotedLiteral) return PsiReference.EMPTY_ARRAY
		val range = ElementManipulators.getValueTextRange(literal)
		val text = range.substring(literal.text)
		if (MdiIcons.isName(text)) return Targets.icon(literal, text, range)
		val args = literal.parent as? JSArgumentList ?: return PsiReference.EMPTY_ARRAY
		if (args.arguments.firstOrNull() != literal) return PsiReference.EMPTY_ARRAY
		val call = args.parent as? JSCallExpression ?: return PsiReference.EMPTY_ARRAY
		val name = (call.methodExpression as? JSReferenceExpression)?.referenceName ?: return PsiReference.EMPTY_ARRAY
		if (name !in TRANSLATE_CALLS) return PsiReference.EMPTY_ARRAY
		return LangKeys.references(literal, text, range)
	}

	/** `icon="mdi-…"` and the like in Vue templates. */
	private fun xml(value: XmlAttributeValue): Array<PsiReference> {
		val range = ElementManipulators.getValueTextRange(value)
		val text = range.substring(value.text)
		return if (MdiIcons.isName(text)) Targets.icon(value, text, range) else PsiReference.EMPTY_ARRAY
	}

	// ---------------------------------------------------------------- PHP

	private fun php(literal: StringLiteralExpression): Array<PsiReference> {
		val range = ElementManipulators.getValueTextRange(literal)
		val text = range.substring(literal.text)
		val fileName = literal.containingFile.originalFile.name

		if (MdiIcons.isName(text)) return Targets.icon(literal, text, range)
		restricted(literal, text, range)?.let { return it }
		mail(literal, text, range, fileName)?.let { return it }

		val hash = Targets.ownerHash(literal)
		val key = hash?.let { PhpArrays.keyOf(it) }
		// `'active' => ['a.*', 'b.*']`: the item has no key of its own, its array does
		val listKey = if (hash == null) enclosingArrayKey(literal) else null
		when {
			key != null && LANG_KEY.matches(key) -> return LangKeys.references(literal, text, range)
			fileName == "navigation.php" && key == "route" -> return Targets.route(literal, text, range)
			fileName == "navigation.php" && (key == "active" || listKey == "active") -> return Targets.routePattern(literal, text, range)
			key == "view" && text.contains('.') -> return Targets.view(literal, text, range)
			key == "partial" && fileName in PARTIAL_DIRS -> return Targets.view(literal, text, range, PARTIAL_DIRS.getValue(fileName))
			key == "html-preset" -> return Targets.htmlPreset(literal, text, range)
		}
		// a PageType's `view(): string { return 'public.types.content'; }`
		if (literal.parent is PhpReturn && (PsiTreeUtil.getParentOfType(literal, Function::class.java) as? Method)?.name == "view") {
			return Targets.view(literal, text, range)
		}
		registryLayoutField(literal, text, range, key)?.let { return it }
		return PsiReference.EMPTY_ARRAY
	}

	private fun enclosingArrayKey(literal: StringLiteralExpression): String? {
		val array = literal.parent as? ArrayCreationExpression ?: literal.parent?.parent as? ArrayCreationExpression ?: return null
		return Targets.ownerHash(array)?.let { PhpArrays.keyOf(it) }
	}

	/**
	 * A field name in a registry entry's `formLayout` (config/cms-blocks.php, cms-popups.php): a bare item,
	 * or a `field` / `repeater` value, resolved against the same entry's `fields`.
	 */
	private fun registryLayoutField(literal: StringLiteralExpression, text: String, range: TextRange, key: String?): Array<PsiReference>? {
		val bare = key == null && (literal.parent is ArrayCreationExpression || literal.parent?.parent is ArrayCreationExpression)
		if (!bare && key != "field" && key != "repeater") return null
		var layout: ArrayHashElement? = null
		for (hash in generateSequence(literal.parent) { it.parent }.filterIsInstance<ArrayHashElement>()) {
			val k = PhpArrays.keyOf(hash)
			if (k == "props" || k == "item") return null
			if (k == "formLayout") {
				layout = hash
				break
			}
		}
		val entry = layout?.parent as? ArrayCreationExpression ?: return null
		val fields = { PhpArrays.entry(entry, "fields")?.let { PhpArrays.value(it) as? ArrayCreationExpression } }
		if (fields() == null) return null
		return Targets.arrayKey(literal, text, range, fields, "field", "No field '$text' in this entry's fields")
	}

	/** `#[Restricted('perm')]` on a class, or on a method (`classScope@perm`). */
	private fun restricted(literal: StringLiteralExpression, text: String, range: TextRange): Array<PsiReference>? {
		val attribute = PsiTreeUtil.getParentOfType(literal, PhpAttribute::class.java) ?: return null
		if (attribute.fqn?.endsWith("\\Restricted") != true) return null
		if (PsiTreeUtil.findChildOfType(attribute, StringLiteralExpression::class.java) != literal) return null
		val permission = when (val owner = attribute.owner) {
			is Method -> {
				val cls = owner.containingClass ?: return null
				val classScope = cls.getAttributes(attribute.fqn ?: return null).firstOrNull()
					?.let { PsiTreeUtil.findChildOfType(it, StringLiteralExpression::class.java)?.contents }
					?: cls.fqn.removePrefix("\\")
				"$classScope@$text"
			}
			is PhpClass -> text
			else -> return null
		}
		return Targets.permission(literal, permission, range)
	}

	/**
	 * Mail template codes passed to MailTemplateRenderer / EmailTemplate, the placeholder keys handed to
	 * `render()`, and `{{ placeholder }}` inside config/mail-templates.php.
	 */
	private fun mail(literal: StringLiteralExpression, text: String, range: TextRange, fileName: String): Array<PsiReference>? {
		if (fileName == "mail-templates.php") return placeholdersInTemplate(literal, text, range)

		val templates = { Targets.mailTemplates(literal) }
		// `render('code', …)` / `forCode('code')` / …
		mailCall(literal)?.let { (_, index) ->
			if (index == 0) return Targets.arrayKey(literal, text, range, templates, "mail template", "Unknown mail template '$text'")
		}
		// `$code = 'code';` later passed as the code
		val assignment = literal.parent as? AssignmentExpression
		val variable = assignment?.variable as? Variable
		if (assignment?.value == literal && variable != null) {
			val fn = PsiTreeUtil.getParentOfType(literal, Function::class.java)
			val used = PsiTreeUtil.findChildrenOfType(fn, Variable::class.java)
				.any { it.name == variable.name && it != variable && mailCall(it)?.second == 0 }
			if (used) return Targets.arrayKey(literal, text, range, templates, "mail template", "Unknown mail template '$text'")
		}
		// a key of the data array: `render($code, ['user_name' => …])`
		val hash = literal.parent?.parent as? ArrayHashElement ?: literal.parent as? ArrayHashElement
		if (hash != null && PhpArrays.unwrap(hash.key) == literal) {
			val array = hash.parent as? ArrayCreationExpression ?: return null
			val (call, index) = mailCall(array) ?: return null
			if (index != 1 || call.name != "render") return null
			val code = codeOf(call.parameters.firstOrNull()) ?: return null
			return Targets.arrayKey(literal, text, range, { Targets.mailPlaceholders(literal, code) }, "placeholder", "Mail '$code' declares no placeholder '$text'")
		}
		return null
	}

	/** The mail call this expression is an argument of, with its position. */
	private fun mailCall(arg: PsiElement): Pair<MethodReference, Int>? {
		val call = arg.parent?.parent as? MethodReference ?: return null
		if (call.name !in MAIL_METHODS) return null // before resolve(), which every string argument would pay for
		val index = call.parameters.indexOf(arg).takeIf { it >= 0 } ?: return null
		val cls = (call.resolve() as? Method)?.containingClass?.name ?: return null
		return if (cls in MAIL_CLASSES) call to index else null
	}

	private fun codeOf(arg: PsiElement?): String? = when (arg) {
		is StringLiteralExpression -> arg.contents
		is Variable -> PsiTreeUtil.findChildrenOfType(PsiTreeUtil.getParentOfType(arg, Function::class.java), AssignmentExpression::class.java)
			.firstOrNull { (it.variable as? Variable)?.name == arg.name }?.value?.let { it as? StringLiteralExpression }?.contents
		else -> null
	}

	/** `{{ name }}` in a template's strings, against that entry's `placeholders`. */
	private fun placeholdersInTemplate(literal: StringLiteralExpression, text: String, range: TextRange): Array<PsiReference>? {
		if (!text.contains("{{")) return null
		val top = Targets.mailTemplates(literal) ?: return null
		val entry = generateSequence(literal.parent) { it.parent }.filterIsInstance<ArrayHashElement>()
			.firstOrNull { it.parent == top } ?: return null
		val code = PhpArrays.keyOf(entry) ?: return null
		return PLACEHOLDER.findAll(text).map { m ->
			val name = m.groups[1]!!
			Targets.arrayKey(
				literal, m.groupValues[1],
				TextRange(range.startOffset + name.range.first, range.startOffset + name.range.last + 1),
				{ Targets.mailPlaceholders(literal, code) }, "placeholder",
				"Mail '$code' declares no placeholder '${m.groupValues[1]}'",
			).single()
		}.toList().toTypedArray()
	}
}
