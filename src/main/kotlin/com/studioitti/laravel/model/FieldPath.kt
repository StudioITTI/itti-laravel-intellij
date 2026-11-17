package com.studioitti.laravel.model

import com.studioitti.laravel.support.DotContinuation
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.jetbrains.php.lang.psi.elements.PhpClass
import org.jetbrains.yaml.psi.YAMLMapping

enum class PathMode {
	/** `name` or a json path `attrs.address.city` */
	FIELD,

	/** `rel:relation[.relation...].field[.json...]` */
	REL,

	/** a bare relation name (`rel.name` in ModelSchema) */
	RELATION,
}

/** What one segment of a path turned out to be. `problem` is set only where the answer is certain. */
class Step(
	val targets: List<PsiElement>,
	val variants: () -> List<LookupElement>,
	val problem: String? = null,
)

/** A field path from a schema, resolved segment by segment against `model`. */
class FieldPath(private val model: PhpClass?, private val segments: List<String>, private val mode: PathMode) {

	val steps: List<Step> by lazy { walk() }

	private fun walk(): List<Step> {
		val out = ArrayList<Step>()
		var cls = model
		var inRelations = mode != PathMode.FIELD
		var json: YAMLMapping? = null
		var lost = false // past an unknown segment or an unresolvable relation: nothing more can be said

		for ((i, segment) in segments.withIndex()) {
			val current = cls
			if (lost || current == null) {
				out += Step(emptyList(), { emptyList() })
				continue
			}
			val isLast = i == segments.lastIndex
			if (inRelations) {
				val offerFields = mode == PathMode.REL && i > 0
				val variants = { relationVariants(current) + if (offerFields) fieldVariants(current) else emptyList() }
				val relation = if (!isLast || mode == PathMode.RELATION) Models.relation(current, segment) else null
				if (relation != null) {
					out += Step(listOf(relation), variants)
					cls = Models.related(relation, current)
					continue
				}
				if (mode == PathMode.RELATION || i == 0) {
					out += Step(emptyList(), variants, "Unknown relation '$segment' on ${current.name}")
					lost = true
					continue
				}
				inRelations = false
				val field = Models.fields(current)[segment]
				if (field == null) {
					out += Step(emptyList(), variants, "Unknown field or relation '$segment' on ${current.name}")
					lost = true
				}
				else {
					out += Step(field.map { it.element }, variants)
					json = field.firstNotNullOfOrNull { Models.nestedFields(it.element) }
					cls = current
				}
				continue
			}
			if (json != null || i > 0) {
				// a json path: only checked against declared nested fields, any json value may be pathed into
				val map = json
				val kv = map?.getKeyValueByKey(segment)
				out += Step(listOfNotNull(kv), { map?.keyValues?.map { field(it.keyText, "json") } ?: emptyList() })
				json = kv?.let { Models.nestedFields(it) }
				if (kv == null) lost = true
				continue
			}
			val field = Models.fields(current)[segment]
			out += Step(
				field?.map { it.element } ?: emptyList(),
				{ fieldVariants(current) },
				if (field == null) "Unknown field '$segment' on ${current.name}" else null,
			)
			json = field?.firstNotNullOfOrNull { Models.nestedFields(it.element) }
			if (field == null) lost = true
		}
		return out
	}

	private fun relationVariants(cls: PhpClass): List<LookupElement> =
		Models.relations(cls).map { m ->
			val target = Models.related(m, cls)?.name ?: "relation"
			LookupElementBuilder.create(m.name).withIcon(AllIcons.Nodes.Method).withTypeText(target)
				.withInsertHandler(if (mode == PathMode.RELATION) null else DotContinuation)
		}

	private fun fieldVariants(cls: PhpClass): List<LookupElement> =
		Models.fields(cls).map { (name, sources) -> field(name, sources.joinToString(", ") { it.kind }) }

	private fun field(name: String, kind: String): LookupElement =
		LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Field).withTypeText(kind)

	companion object {
		/** References for a path at `range` inside `element`; a `rel:` prefix switches FIELD to REL. */
		fun references(element: PsiElement, model: PhpClass?, text: String, range: TextRange, mode: PathMode): Array<PsiReference> {
			val trimmed = text.trimEnd()
			if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return PsiReference.EMPTY_ARRAY
			var effective = mode
			var offset = range.startOffset
			var body = trimmed
			if (mode == PathMode.FIELD && trimmed.startsWith("rel:")) {
				effective = PathMode.REL
				offset += 4
				body = trimmed.substring(4)
			}
			val segments = body.split('.')
			val path = FieldPath(model, segments, effective)
			val refs = ArrayList<PsiReference>()
			segments.forEachIndexed { i, segment ->
				if (segment.isNotEmpty()) refs += FieldPathReference(element, TextRange(offset, offset + segment.length), path, i)
				offset += segment.length + 1
			}
			return refs.toTypedArray()
		}
	}
}

class FieldPathReference(
	element: PsiElement,
	range: TextRange,
	private val path: FieldPath,
	private val index: Int,
) : PsiPolyVariantReferenceBase<PsiElement>(element, range, true) {

	val step: Step? get() = path.steps.getOrNull(index)

	override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
		PsiElementResolveResult.createResults(step?.targets ?: emptyList())

	override fun getVariants(): Array<Any> = step?.variants?.invoke()?.toTypedArray() ?: emptyArray()

	/** Renaming an accessor method renames the snake_case attribute it provides. */
	override fun handleElementRename(newElementName: String): PsiElement {
		val current = value
		val accessor = step?.targets?.filterIsInstance<com.jetbrains.php.lang.psi.elements.Method>()
			?.any { it.name != current && Models.snake(it.name.removePrefix("get").removeSuffix("Attribute")) == current } == true
		val name = if (!accessor) newElementName
		else Models.snake(Regex("^get(\\w+)Attribute$").find(newElementName)?.groupValues?.get(1) ?: newElementName)
		return super.handleElementRename(name)
	}
}
