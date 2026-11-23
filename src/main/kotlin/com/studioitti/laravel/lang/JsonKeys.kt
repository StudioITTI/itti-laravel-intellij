package com.studioitti.laravel.lang

import com.studioitti.laravel.support.DotContinuation
import com.studioitti.laravel.support.LabelledTarget
import com.studioitti.laravel.support.LaravelProject
import com.studioitti.laravel.support.relativeTo
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult

/**
 * Frontend translation keys (SPEC §4.5, §6.14): the admin SPA's laravel-vue-i18n reads only `lang/<locale>.json`,
 * whole keys in a flat object, never the PHP lang files.
 */
object JsonKeys {
	private val DIRS = listOf("resources/lang", "lang")
	private val LANG_JSON = Regex("^(resources/)?lang/[^/]+\\.json$")

	class Entry(val locale: String, val file: JsonFile, val property: JsonProperty) {
		val text: String? get() = (property.value as? JsonStringLiteral)?.value
		val target: PsiElement get() = property.nameElement
	}

	/** `<locale>.json` directly in `lang/` or `resources/lang/`, `en` first. */
	fun files(context: PsiElement): List<Pair<String, JsonFile>> {
		val root = LaravelProject.root(context) ?: return emptyList()
		val manager = PsiManager.getInstance(context.project)
		return DIRS.mapNotNull { root.findFileByRelativePath(it) }
			.flatMap { base -> base.children.filter { !it.isDirectory && it.extension == "json" } }
			.sortedWith(compareBy({ it.nameWithoutExtension != "en" }, { it.nameWithoutExtension }))
			.mapNotNull { vf -> (manager.findFile(vf) as? JsonFile)?.let { vf.nameWithoutExtension to it } }
	}

	fun locales(context: PsiElement): List<String> = files(context).map { it.first }.distinct()

	fun resolve(context: PsiElement, key: String): List<Entry> = files(context).mapNotNull { (locale, file) ->
		(file.topLevelValue as? JsonObject)?.findProperty(key)?.let { Entry(locale, file, it) }
	}

	fun isLangFile(file: PsiFile?): Boolean {
		val vf = file?.originalFile?.virtualFile ?: return false
		val root = LaravelProject.root(file) ?: return false
		return VfsUtilCore.getRelativePath(vf, root)?.let { LANG_JSON.matches(it) } == true
	}

	/** A key string of a JSON lang file: a frontend key's declaration (SPEC §8.1). */
	fun isKey(element: PsiElement): Boolean =
		element is JsonStringLiteral && (element.parent as? JsonProperty)?.nameElement == element && isLangFile(element.containingFile)

	/** The distinct next segments after `prefix` across the JSON keys; a segment with more after it is a branch. */
	fun variants(context: PsiElement, prefix: List<String>): List<LookupElement> {
		val start = if (prefix.isEmpty()) "" else prefix.joinToString(".") + "."
		val seen = LinkedHashMap<String, LookupElement>()
		for ((_, file) in files(context)) {
			for (property in (file.topLevelValue as? JsonObject)?.propertyList.orEmpty()) {
				val rest = property.name.takeIf { it.startsWith(start) }?.substring(start.length)?.takeIf { it.isNotEmpty() } ?: continue
				val segment = rest.substringBefore('.')
				val branch = rest.contains('.')
				val id = if (branch) "$segment." else segment
				if (id in seen) continue
				seen[id] = if (branch) {
					LookupElementBuilder.create(segment).withIcon(AllIcons.Nodes.Folder).withTypeText("{…}").withInsertHandler(DotContinuation)
				}
				else {
					val text = (property.value as? JsonStringLiteral)?.value ?: ""
					LookupElementBuilder.create(segment).withIcon(AllIcons.Nodes.Property).withTypeText(text.take(60), true)
				}
			}
		}
		return seen.values.toList()
	}

	/** One reference per segment of `key` at `range` inside `element`; the same skip rules as PHP lang keys. */
	fun references(element: PsiElement, key: String, range: TextRange): Array<PsiReference> {
		val trimmed = key.trimEnd()
		if (trimmed.isEmpty() || trimmed.contains("::") || trimmed.any { it.isWhitespace() || it == '/' || it == '$' || it == '{' }) {
			return PsiReference.EMPTY_ARRAY
		}
		val segments = trimmed.split('.')
		val refs = ArrayList<PsiReference>()
		var offset = range.startOffset
		segments.forEachIndexed { i, segment ->
			if (segment.isNotEmpty()) refs += JsonKeyReference(element, TextRange(offset, offset + segment.length), segments, i)
			offset += segment.length + 1
		}
		return refs.toTypedArray()
	}
}

/** A segment of a frontend key: every segment resolves to the whole key's entry in each locale's JSON file. */
class JsonKeyReference(
	element: PsiElement,
	range: TextRange,
	val segments: List<String>,
	val index: Int,
) : PsiPolyVariantReferenceBase<PsiElement>(element, range, true) {

	val isLast: Boolean get() = index == segments.lastIndex
	val key: String get() = segments.joinToString(".")

	override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
		PsiElementResolveResult.createResults(JsonKeys.resolve(element, key).map {
			LabelledTarget(it.target, "${it.locale}: ${(it.text ?: key).take(80)}", it.file.virtualFile?.relativeTo(element), AllIcons.Nodes.Property)
		})

	/** Only the last segment is a usage of the key, so a rename touches one segment per string. */
	override fun isReferenceTo(target: PsiElement): Boolean =
		isLast && JsonKeys.resolve(element, key).any {
			element.manager.areElementsEquivalent(it.target, target) || element.manager.areElementsEquivalent(it.property, target)
		}

	override fun getVariants(): Array<Any> = JsonKeys.variants(element, segments.subList(0, index)).toTypedArray()
}
