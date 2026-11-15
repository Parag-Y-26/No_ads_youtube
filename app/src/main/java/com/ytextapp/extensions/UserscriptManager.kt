package com.ytextapp.extensions

import android.content.Context
import java.io.File

class UserscriptManager(private val context: Context) {

    private val scriptsDir: File by lazy {
        File(context.filesDir, "userscripts").also { dir ->
            if (!dir.exists()) dir.mkdirs()
        }
    }

    fun getScripts(): List<String> {
        if (!scriptsDir.exists()) return emptyList()

        return scriptsDir.listFiles { file -> file.extension == "js" }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                try {
                    file.readText()
                } catch (e: Exception) {
                    null
                }
            }
            ?: emptyList()
    }

    fun getScriptNames(): List<String> {
        if (!scriptsDir.exists()) return emptyList()
        return scriptsDir.listFiles { file -> file.extension == "js" }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    fun saveScript(name: String, content: String) {
        val sanitizedName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val fileName = if (sanitizedName.endsWith(".js")) sanitizedName else "$sanitizedName.js"
        File(scriptsDir, fileName).writeText(content)
    }

    fun deleteScript(name: String) {
        File(scriptsDir, name).let { file ->
            if (file.exists() && file.parentFile == scriptsDir) {
                file.delete()
            }
        }
    }
}
