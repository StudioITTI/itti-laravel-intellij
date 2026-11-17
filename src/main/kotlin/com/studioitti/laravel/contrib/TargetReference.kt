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
}
