package com.github.yutaplug.newthemes

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.util.LongSparseArray
import android.os.Build
import android.widget.TextView
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import com.aliucord.Utils
import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import de.robv.android.xposed.XposedBridge

/** Changes only Discord's neutral dark palette, retaining accents and status colors. */
internal class ThemePalette(private val choice: () -> ThemeChoice) {
    private val originals = HashMap<Int, Int>()
    private lateinit var resources: Resources
    private val ash = intArrayOf(0x4e5058, 0x404249, 0x35373c, 0x303238, 0x2b2d31, 0x232428, 0x1e1f22, 0x111214)
    private val dark = intArrayOf(0x3f3f46, 0x29292e, 0x1a1a1e, 0x17171b, 0x131316, 0x111114, 0x0e0e11, 0x08080a)
    private val onyx = intArrayOf(0x333338, 0x111113, 0x000000, 0x08080b, 0x08080b, 0x08080b, 0x08080b, 0x000000)

    fun start(context: Context, patcher: PatcherAPI) {
        resources = context.resources
        val names = arrayOf("primary_500", "primary_560", "primary_600", "primary_630", "primary_660", "primary_700", "primary_800", "primary_900")
        names.forEachIndexed { index, name ->
            val id = Utils.getResId(name, "color")
            if (id != 0) {
                @Suppress("DEPRECATION")
                val color = if (Build.VERSION.SDK_INT >= 23) context.resources.getColor(id, null) else context.resources.getColor(id)
                originals[color] = index
            }
        }
        fun mapped(color: Int): Int? {
            if (choice() == ThemeChoice.LIGHT) return null
            val index = originals[color or Color.BLACK] ?: return null
            val palette = when (choice()) {
                ThemeChoice.DARK -> dark
                ThemeChoice.ONYX -> onyx
                else -> ash
            }
            return (color and -0x1000000) or palette[index]
        }
        val int = Int::class.javaPrimitiveType!!
        val getColor = if (Build.VERSION.SDK_INT >= 23) {
            Resources::class.java.getDeclaredMethod("getColor", int, Resources.Theme::class.java)
        } else Resources::class.java.getDeclaredMethod("getColor", int)
        patcher.patch(getColor, Hook {
            mapped(it.result as Int)?.let { color -> it.result = color }
        })
        // XML android:background colors become ColorDrawables directly from a
        // TypedValue; that path does not call getColor or ColorDrawable.setColor.
        // Change the value before the loader selects its color-drawable cache key.
        val loadDrawable = when {
            Build.VERSION.SDK_INT >= 26 -> Class.forName("android.content.res.ResourcesImpl").getDeclaredMethod(
                "loadDrawable", Resources::class.java, TypedValue::class.java, int, int, Resources.Theme::class.java,
            )
            Build.VERSION.SDK_INT >= 24 -> Class.forName("android.content.res.ResourcesImpl").getDeclaredMethod(
                "loadDrawable", Resources::class.java, TypedValue::class.java, int, Resources.Theme::class.java,
                Boolean::class.javaPrimitiveType,
            )
            else -> Resources::class.java.getDeclaredMethod(
                "loadDrawable", TypedValue::class.java, int, Resources.Theme::class.java,
            )
        }
        val valueIndex = if (Build.VERSION.SDK_INT >= 24) 1 else 0
        patcher.patch(loadDrawable, PreHook {
            val value = it.args[valueIndex] as TypedValue
            if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                mapped(value.data)?.let { color -> value.data = color }
            }
        })
        // These callers may already have inlined the unpatched drawable loader.
        XposedBridge.deoptimizeMethod(TypedArray::class.java.getDeclaredMethod("getDrawableForDensity", int, int))
        XposedBridge.deoptimizeMethod(Resources::class.java.getDeclaredMethod(
            "getDrawableForDensity", int, int, Resources.Theme::class.java,
        ))
        patcher.patch(Resources.Theme::class.java.getDeclaredMethod("resolveAttribute", int, TypedValue::class.java, Boolean::class.javaPrimitiveType), Hook {
            if (it.result == true) {
                val value = it.args[1] as TypedValue
                if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                    mapped(value.data)?.let { color -> value.data = color }
                }
            }
        })
        patcher.patch(TypedArray::class.java.getDeclaredMethod("getColor", int, int), Hook {
            mapped(it.result as Int)?.let { color -> it.result = color }
        })
        patcher.patch(TypedArray::class.java.getDeclaredMethod("getColorStateList", int), Hook {
            val list = it.result as ColorStateList? ?: return@Hook
            // Stateful lists are handled by getColorForState without losing states.
            if (!list.isStateful) mapped(list.defaultColor)?.let { color -> it.result = ColorStateList.valueOf(color) }
        })
        patcher.patch(ColorStateList::class.java.getDeclaredMethod("getColorForState", IntArray::class.java, int), Hook {
            mapped(it.result as Int)?.let { color -> it.result = color }
        })
        patcher.patch(ColorStateList::class.java.getDeclaredMethod("getDefaultColor"), Hook {
            mapped(it.result as Int)?.let { color -> it.result = color }
        })
        patcher.patch(ColorDrawable::class.java.getDeclaredMethod("setColor", int), PreHook {
            mapped(it.args[0] as Int)?.let { color -> it.args[0] = color }
        })
        patcher.patch(GradientDrawable::class.java.getDeclaredMethod("setColor", int), PreHook {
            mapped(it.args[0] as Int)?.let { color -> it.args[0] = color }
        })
        // Framework callers can inline ColorStateList/ColorDrawable accessors.
        // Refresh those compiled callers as in Aliucord's native theming path.
        mapOf(
            TextView::class.java to "updateTextColors",
            RippleDrawable::class.java to "updateRipplePaint",
            Drawable::class.java to "updateBlendModeFilter",
            GradientDrawable::class.java to "updateLocalState",
            android.view.View::class.java to "setBackgroundColor",
        ).forEach { (type, name) ->
            type.declaredMethods.filter { it.name == name }.forEach { XposedBridge.deoptimizeMethod(it) }
        }
        clearCaches()
    }

    /** Cached drawables otherwise retain the previous palette across recreation. */
    fun clearCaches() {
        if (!::resources.isInitialized) return
        fun field(owner: Any, name: String): Any? {
            var type: Class<*>? = owner.javaClass
            while (type != null) {
                val candidate = type
                val result = runCatching { candidate.getDeclaredField(name).apply { isAccessible = true }.get(owner) }
                if (result.isSuccess) return result.getOrNull()
                type = candidate.superclass
            }
            return null
        }
        fun clear(value: Any?) {
            when (value) {
                is MutableMap<*, *> -> value.clear()
                is LongSparseArray<*> -> value.clear()
            }
        }
        val impl = field(resources, "mResourcesImpl") ?: resources
        arrayOf("mDrawableCache", "mColorDrawableCache", "mColorStateListCache", "mComplexColorCache").forEach { name ->
            val cache = field(impl, name) ?: return@forEach
            synchronized(cache) {
                clear(cache)
                arrayOf("mThemedEntries", "mUnthemedEntries", "mNullThemedEntries").forEach { clear(field(cache, it)) }
            }
        }
        // Both libraries cache XML ColorStateLists outside framework resources.
        arrayOf(AppCompatResources::class.java, ResourcesCompat::class.java).forEach { type ->
            runCatching {
                val lock = type.getDeclaredField("sColorStateCacheLock").apply { isAccessible = true }.get(null)!!
                synchronized(lock) {
                    clear(type.getDeclaredField("sColorStateCaches").apply { isAccessible = true }.get(null))
                }
            }
        }
    }
}
