package com.studioitti.laravel.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult

/**
 * One segment of a translation key. The first resolves to the group file of each locale,
 * the rest to the array key in each locale, so Ctrl+click offers every language.
 */
class LangKeyReference(
	element: PsiElement,
	range: TextRange,
	val segments: List<String>,
	val index: Int,
) : PsiPolyVariantReferenceBase<PsiElement>(element, range, true) {

	val isLast: Boolean get() = index == segments.lastIndex

	override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
		PsiElementResolveResult.createResults(LangKeys.resolve(element, segments.subList(0, index + 1)).map { LangTarget(it) })

	/** Usages are asked about the PHP key itself, not the wrapper it resolves to. */
	override fun isReferenceTo(target: PsiElement): Boolean =
		LangKeys.resolve(element, segments.subList(0, index + 1)).any { element.manager.areElementsEquivalent(it.target, target) }

	override fun getVariants(): Array<Any> =
		LangKeys.variants(element, segments.subList(0, index)).toTypedArray()
}
