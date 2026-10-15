package dev.abysser.yao.ui

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.abysser.yao.K
import dev.abysser.yao.Model

/**
 * A text target for the phone's input method that holds no text of its own. Every
 * operation of the input method maps one-to-one onto the computer: committed text is
 * typed there, deletions become Backspace, the action key becomes Enter. Text still
 * being composed (pinyin, a word being predicted) stays on the phone until committed.
 * Reporting an empty field also keeps the input method from reaching back to rewrite
 * text that is already on the computer.
 */
class ImeBridgeView(context: Context, private val model: Model, private val onComposing: (String) -> Unit) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(info: EditorInfo): InputConnection {
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        info.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        info.initialSelStart = 0
        info.initialSelEnd = 0
        return Bridge()
    }

    fun show() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
    }

    fun hide() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    private inner class Bridge : BaseInputConnection(this@ImeBridgeView, false) {
        private var composing = ""

        /**
         * The composing text also shows, underlined, in the computer's focused field.
         * A commit replaces it there by itself, so clearing is only sent when the
         * composition is dropped without committing.
         */
        private fun setComposing(s: String, send: Boolean = true) {
            if (s == composing) return
            composing = s
            onComposing(s)
            if (send) model.preedit(s)
        }

        /** Types [s] on the computer; line breaks become Enter. */
        private fun type(s: String) {
            val lines = s.split('\n')
            lines.forEachIndexed { i, line ->
                if (line.isNotEmpty()) model.text(line)
                if (i < lines.size - 1) model.tap(K.ENTER)
            }
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            setComposing("", send = false)
            type(text.toString())
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            setComposing(text.toString())
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composing.isNotEmpty()) {
                val s = composing
                setComposing("", send = false)
                type(s)
            }
            return true
        }

        override fun setComposingRegion(start: Int, end: Int) = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (composing.isEmpty()) {
                repeat(beforeLength) { model.tap(K.BACKSPACE) }
                repeat(afterLength) { model.tap(K.DELETE) }
            }
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
            deleteSurroundingText(beforeLength, afterLength)

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> model.tap(K.BACKSPACE)
                KeyEvent.KEYCODE_FORWARD_DEL -> model.tap(K.DELETE)
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> model.tap(K.ENTER)
                KeyEvent.KEYCODE_TAB -> model.tap(K.TAB)
                KeyEvent.KEYCODE_DPAD_LEFT -> model.tap(K.LEFT)
                KeyEvent.KEYCODE_DPAD_RIGHT -> model.tap(K.RIGHT)
                KeyEvent.KEYCODE_DPAD_UP -> model.tap(K.UP)
                KeyEvent.KEYCODE_DPAD_DOWN -> model.tap(K.DOWN)
                else -> {
                    val c = event.unicodeChar
                    if (c != 0) type(String(Character.toChars(c)))
                }
            }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            finishComposingText()
            model.tap(K.ENTER)
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int) = 0
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    }
}
