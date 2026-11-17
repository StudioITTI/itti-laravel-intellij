package com.studioitti.laravel.contrib

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import javax.swing.Icon

/**
 * A reference that is told what it points at. `unknown` is the warning shown when nothing resolves;
 * null means an unresolved value is not worth flagging.
 */
class TargetReference(
	element: PsiElement,
	range: TextRange,
	private val targets: () -> List<PsiElement>,
	private val variants: () -> List<LookupElement> = { emptyList() },
	val unknown: String? = null,
	val severity: HighlightSeverity = HighlightSeverity.WARNING,
	/** drawn in the gutter beside the value (mdi icons) */
	val gutterIcon: (() -> Icon?)? = null,
) : PsiPolyVariantReferenceBase<PsiElement>(element, range, true) {

	val resolved: List<PsiElement> by lazy { targets() }

	override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
		PsiElementResolveResult.createResults(resolved)

	override fun getVariants(): Array<Any> = variants().toTypedArray()

	/** Usages are asked about the real element, not the wrapper a chooser row needs; a slot by its attribute. */
	override fun isReferenceTo(target: PsiElement): Boolean = resolved.any { r ->
		val e = (r as? com.studioitti.laravel.support.LabelledTarget)?.element
			?: (r as? com.studioitti.laravel.lang.LangTarget)?.element ?: r
		element.manager.areElementsEquivalent(e, target) || (target is com.intellij.psi.xml.XmlAttribute && e.parent == target)
	}
}
