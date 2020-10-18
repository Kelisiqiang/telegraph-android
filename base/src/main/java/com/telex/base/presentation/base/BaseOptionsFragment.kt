package com.telex.base.presentation.base

import android.app.Dialog
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.telex.base.R
import com.telex.base.extention.color
import com.telex.base.extention.getColorFromAttr
import com.telex.base.extention.setGone
import kotlinx.android.synthetic.main.fragment_bottom_sheet_options.*

/**
 * @author Sergey Petrov
 */
abstract class BaseOptionsFragment : BaseBottomSheetFragment() {

    override val layout: Int = R.layout.fragment_bottom_sheet_options

    @StringRes
    protected open val titleResId: Int? = null

    override fun setupView(dialog: Dialog) {
        dialog.titleTextView.apply {
            if (titleResId != null) {
                setGone(false)
                text = getString(requireNotNull(titleResId))
            } else {
                setGone(true)
            }
        }
    }

    protected open fun addOptions(vararg options: Option) {
        options.forEach { option -> addOption(option) }
    }

    protected fun addOption(option: Option) {
        val textView = TextView(ContextThemeWrapper(context, R.style.OptionTextViewStyle))
        textView.text = getString(option.title)

        var iconDrawable = ContextCompat.getDrawable(context, option.icon) ?: throw IllegalArgumentException("drawable can't be null")
        iconDrawable = DrawableCompat.wrap(iconDrawable)

        if (option.color == null) {
            DrawableCompat.setTint(iconDrawable, context.getColorFromAttr(R.attr.colorControlNormal))
        } else {
            DrawableCompat.setTint(iconDrawable, context.color(option.color))
            textView.setTextColor(context.color(option.color))
        }

        textView.setCompoundDrawablesWithIntrinsicBounds(iconDrawable, null, null, null)

        textView.setOnClickListener {
            option.onClick?.invoke()
            dismiss()
        }

        dialog?.containerLayout?.addView(textView)
    }

    class Option(@DrawableRes val icon: Int, @StringRes val title: Int, @ColorRes val color: Int? = null, var onClick: (() -> Unit)? = null)
}
