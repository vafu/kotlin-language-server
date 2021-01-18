package org.javacs.kt

import java.nio.file.Path
import java.nio.file.Files

val settings = mutableMapOf<Path, Map<SettingKey, SettingParam>>()
private val SETTING_FILENAME = "kls_settings"

fun parseSettings(workspaceRoot: Path) =
    settings.getOrPut(workspaceRoot) { 
        Files.readAllLines(
            workspaceRoot.resolve(SETTING_FILENAME)
        )
            .mapNotNull {
                val split = it.split(":")
                val value = split[1]

                val key = SettingKey.values()
                    .find { it.name == split[0] }

                val paramClass = key?.paramClass
                val param = 
                    if (paramClass != null) {
                        SettingParam.parse(value, paramClass)
                    } else null

                if (param != null && key != null) {
                    key to param
                } else null
            }
            .toMap()

    }

inline fun <reified T: SettingParam> getSettingParam(
    key: SettingKey,
    workspaceRoot: Path
): T = if (key.paramClass == T::class.java) {
        (parseSettings(workspaceRoot).get(key) ?: key.defaultParam) as T
    } else throw IllegalArgumentException("reified param and key doesn't match")

private data class ListSetting(
    val key: String, 
    val params: List<String>
)

sealed class SettingParam {

    data class ListParam(val params: List<String>): SettingParam()
    data class BoolParam(val param: Boolean): SettingParam()

    companion object {
        fun parse(
            paramString: String, 
            paramClass: Class<out SettingParam>
        ): SettingParam? =

            when(paramClass) {
            ListParam::class.java -> 
                paramString.split(",")
                    .map { 
                        it.removePrefix("\"")
                            .removeSuffix("\"")
                    }
                    .let(::ListParam)

            BoolParam::class.java ->
                BoolParam(paramString.toBoolean())

            else -> null
        }
    }
}

enum class SettingKey(val paramClass: Class<out SettingParam>, val defaultParam: SettingParam) { 

    WHITELIST_PATH(SettingParam.ListParam::class.java, SettingParam.ListParam(emptyList())),
    WHITELIST_ONLY(SettingParam.BoolParam::class.java, SettingParam.BoolParam(false))
}
