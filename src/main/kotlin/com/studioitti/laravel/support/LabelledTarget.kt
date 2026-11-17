package com.studioitti.laravel.support

import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import javax.swing.Icon

/** A target with its own Ctrl+click chooser row (text, location), where the element's own would be identical. */
class LabelledTarget(val element: PsiElement, private val text: String, private val location: String?, private val icon: Icon?) : FakePsiElement() {
	override fun getParent(): PsiElement = element
	override fun getContainingFile(): PsiFile? = element.containingFile
	override fun getTextRange() = element.textRange
	override fun getTextOffset() = element.textOffset
	override fun getText(): String? = element.text
	override fun getNavigationElement(): PsiElement = element
	override fun isValid() = element.isValid
	override fun isPhysical() = false

	override fun getName(): String = text
	override fun getPresentableText(): String = text
	override fun getLocationString(): String? = location
	override fun getIcon(open: Boolean): Icon? = icon

	override fun canNavigate() = true
	override fun navigate(requestFocus: Boolean) = (element as? Navigatable)?.navigate(requestFocus) ?: Unit
	override fun navigationRequest(): NavigationRequest? = (element as? Navigatable)?.navigationRequest()

	override fun equals(other: Any?) = other is LabelledTarget && other.element == element
	override fun hashCode() = element.hashCode()
}
