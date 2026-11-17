package com.studioitti.laravel.support

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement

/** After picking a segment that has children (a lang group, a relation), add the `.` and reopen completion. */
object DotContinuation : InsertHandler<LookupElement> {
	override fun handleInsert(context: InsertionContext, item: LookupElement) {
		val doc = context.document
		val offset = context.tailOffset
		if (offset >= doc.textLength || doc.charsSequence[offset] != '.') {
			doc.insertString(offset, ".")
		}
		context.editor.caretModel.moveToOffset(offset + 1)
		AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
	}
}
