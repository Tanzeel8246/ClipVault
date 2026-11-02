package com.clipvault.mobile.keyboard

import android.view.inputmethod.InputConnection

/**
 * InputConnectionController — Clean abstraction over Android InputConnection operations.
 * Encapsulates text insertion, deletion, context-menu operations, and cursor navigation.
 */
object InputConnectionController {

    fun commitText(ic: InputConnection?, text: String, newCursorPosition: Int = 1): Boolean {
        return ic?.commitText(text, newCursorPosition) ?: false
    }

    fun deleteBack(ic: InputConnection?, count: Int = 1): Boolean {
        if (ic == null) return false
        // If there is selected text, delete selection first
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            return ic.commitText("", 1)
        }
        return ic.deleteSurroundingText(count, 0)
    }

    fun selectAll(ic: InputConnection?): Boolean {
        return ic?.performContextMenuAction(android.R.id.selectAll) ?: false
    }

    fun cut(ic: InputConnection?): Boolean {
        return ic?.performContextMenuAction(android.R.id.cut) ?: false
    }

    fun copy(ic: InputConnection?): Boolean {
        return ic?.performContextMenuAction(android.R.id.copy) ?: false
    }

    fun paste(ic: InputConnection?): Boolean {
        return ic?.performContextMenuAction(android.R.id.paste) ?: false
    }

    fun undo(ic: InputConnection?): Boolean {
        return ic?.performContextMenuAction(android.R.id.undo) ?: false
    }

    fun redo(ic: InputConnection?): Boolean {
        return ic?.performContextMenuAction(android.R.id.redo) ?: false
    }

    fun moveCursorHome(ic: InputConnection?): Boolean {
        return ic?.setSelection(0, 0) ?: false
    }

    fun moveCursorEnd(ic: InputConnection?): Boolean {
        if (ic == null) return false
        val len = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
        return ic.setSelection(len, len)
    }

    fun moveCursor(ic: InputConnection?, delta: Int): Boolean {
        if (ic == null) return false
        val lenBefore = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
        val target = (lenBefore + delta).coerceAtLeast(0)
        return ic.setSelection(target, target)
    }
}
