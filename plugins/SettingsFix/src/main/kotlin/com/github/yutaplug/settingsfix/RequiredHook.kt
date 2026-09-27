package com.github.yutaplug.settingsfix

import com.aliucord.api.PatcherAPI
import de.robv.android.xposed.XC_MethodHook

/** The class/name overload swallows failures; the Member overload propagates them. */
internal fun PatcherAPI.patchRequired(
    clazz: Class<*>,
    method: String,
    parameterTypes: Array<Class<*>> = emptyArray(),
    hook: XC_MethodHook,
) {
    val member = clazz.getDeclaredMethod(method, *parameterTypes)
    patch(member, hook)
}
