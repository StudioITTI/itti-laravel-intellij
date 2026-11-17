package com.studioitti.laravel.contrib

import com.studioitti.laravel.lang.LangKeys
import com.studioitti.laravel.model.FieldPath
import com.studioitti.laravel.model.Models
import com.studioitti.laravel.model.PathMode
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.studioitti.laravel.support.PhpArrays
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

	fun of(element: PsiElement): Array<PsiReference> = when (element) {
		is YAMLScalar -> yaml(element)
		is JSLiteralExpression -> js(element)
		is StringLiteralExpression -> php(element)
		else -> PsiReference.EMPTY_ARRAY
	}

	private fun yaml(scalar: YAMLScalar): Array<PsiReference> {
		val range = ElementManipulators.getValueTextRange(scalar)
		val text = range.substring(scalar.text)
		val kv = scalar.parent as? YAMLKeyValue
		if (kv != null && LANG_KEY.matches(kv.keyText)) return LangKeys.references(scalar, text, range)

		if (scalar.containingFile.originalFile.name !in SCHEMA_FILES) return PsiReference.EMPTY_ARRAY
		val path = yamlPath(scalar)
		val joined = path.joinToString(".")
		val model = Models.forSchemaFile(scalar.containingFile) ?: return PsiReference.EMPTY_ARRAY

		return when {
			joined in setOf("list.columns.[]", "list.columns.[].field", "list.search.[]", "list.totals.[].field")
				|| FILTER_PATH.matches(joined)
				|| isLayoutField(path) ->
				FieldPath.references(scalar, model, text, range, PathMode.FIELD)

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

	/**
	 * A bare item of a formLayout sequence, or a `field:` / `repeater:` value. Not inside a repeater's
	 * `item` (named relative to the item) nor `props` (passed through to Vuetify).
	 */
	private fun isLayoutField(path: List<String>): Boolean {
		if (path.size < 3 || path[0] !in setOf("edit", "create") || path[1] != "formLayout") return false
		if ("item" in path || "props" in path) return false
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

	/** The first argument of `t('…')`, `$t('…')` and friends, in scripts and Vue templates. */
	private fun js(literal: JSLiteralExpression): Array<PsiReference> {
		if (!literal.isQuotedLiteral) return PsiReference.EMPTY_ARRAY
		val args = literal.parent as? JSArgumentList ?: return PsiReference.EMPTY_ARRAY
		if (args.arguments.firstOrNull() != literal) return PsiReference.EMPTY_ARRAY
		val call = args.parent as? JSCallExpression ?: return PsiReference.EMPTY_ARRAY
		val name = (call.methodExpression as? JSReferenceExpression)?.referenceName ?: return PsiReference.EMPTY_ARRAY
		if (name !in TRANSLATE_CALLS) return PsiReference.EMPTY_ARRAY
		val range = ElementManipulators.getValueTextRange(literal)
		return LangKeys.references(literal, range.substring(literal.text), range)
	}

	/** `'labelKey' => 'admin.…'` in PHP arrays (config registries, page types). */
	private fun php(literal: StringLiteralExpression): Array<PsiReference> {
		val hash = PsiTreeUtil.getParentOfType(literal, ArrayHashElement::class.java) ?: return PsiReference.EMPTY_ARRAY
		if (PhpArrays.value(hash) != literal) return PsiReference.EMPTY_ARRAY
		val key = PhpArrays.keyOf(hash) ?: return PsiReference.EMPTY_ARRAY
		if (!LANG_KEY.matches(key)) return PsiReference.EMPTY_ARRAY
		val range = ElementManipulators.getValueTextRange(literal)
		return LangKeys.references(literal, range.substring(literal.text), range)
	}
}
