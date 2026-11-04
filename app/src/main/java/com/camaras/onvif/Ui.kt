package com.camaras.onvif

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    val BLUE: Int = Color.parseColor("#1565C0")
    val BLUE_LIGHT: Int = Color.parseColor("#E3F2FD")
    val BG: Int = Color.parseColor("#F1F5F9")
    val BORDER: Int = Color.parseColor("#E2E8F0")
    val TEXT: Int = Color.parseColor("#0F172A")
    val MUTED: Int = Color.parseColor("#64748B")
    val GREEN: Int = Color.parseColor("#2E7D32")
    val GREEN_LIGHT: Int = Color.parseColor("#E8F5E9")
    val GRAY_LIGHT: Int = Color.parseColor("#EEF2F6")
    val RED: Int = Color.parseColor("#C62828")
    val RED_LIGHT: Int = Color.parseColor("#FDECEA")
    val DARK: Int = Color.parseColor("#0F172A")
    val DARK2: Int = Color.parseColor("#1E293B")
    val SOFT: Int = Color.parseColor("#94A3B8")

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun round(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(ctx, radiusDp).toFloat()
        if (strokeDp > 0) g.setStroke(dp(ctx, strokeDp), strokeColor)
        return g
    }

    fun bottomRound(ctx: Context, color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        val r = dp(ctx, radiusDp).toFloat()
        g.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        return g
    }

    fun button(ctx: Context, text: String, bg: Int, fg: Int): Button {
        val b = Button(ctx)
        b.text = text
        b.setAllCaps(false)
        b.setTextColor(fg)
        b.textSize = 15f
        b.stateListAnimator = null
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12))
        b.background = round(ctx, bg, 24)
        return b
    }

    fun label(ctx: Context, text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    fun field(ctx: Context, hint: String, value: String, password: Boolean): EditText {
        val e = EditText(ctx)
        e.hint = hint
        e.setText(value)
        e.setSingleLine()
        e.textSize = 15f
        if (password) {
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        e.setTextColor(TEXT)
        e.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        e.background = round(ctx, BG, 14, BORDER, 1)
        return e
    }

    fun lp(ctx: Context, topDp: Int): LinearLayout.LayoutParams {
        val l = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        l.topMargin = dp(ctx, topDp)
        return l
    }

    fun show(ctx: Context, d: AlertDialog) {
        d.show()
        d.window?.setBackgroundDrawable(round(ctx, Color.WHITE, 24))
    }
}
