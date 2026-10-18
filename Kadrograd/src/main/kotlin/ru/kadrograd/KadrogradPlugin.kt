package ru.kadrograd

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KadrogradPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences("kadrograd_settings_v1", Context.MODE_PRIVATE)
        val defaults = linkedMapOf("zona" to "https://w1.zona.im", "vibe" to "https://kinovibe.cc", "libria" to "https://anilibria.top", "vost_web" to "https://v14.vost.pw", "rezka" to "https://hdrezka-home.tv", "filmix" to "https://filmix.gg")
        fun saved(key: String) = StreamTools.origin(prefs.getString(key, defaults.getValue(key)).orEmpty()) ?: defaults.getValue(key)
        val rezka = RezkaProvider(saved("rezka"))
        val filmix = FilmixProvider(saved("filmix"))
        val libria = LibriaProvider(saved("libria"))
        val vost = VostProvider(saved("vost_web"))
        val vibe = VibeProvider(saved("vibe"))
        val zona = ZonaProvider(saved("zona"))
        registerMainAPI(zona)
        if (prefs.getBoolean("experimental_vibe", false)) registerMainAPI(vibe)
        if (prefs.getBoolean("experimental_rezka", false)) registerMainAPI(rezka)
        if (prefs.getBoolean("experimental_filmix", false)) registerMainAPI(filmix)
        registerMainAPI(libria)
        registerMainAPI(vost)

        openSettings = {
            val padding = (20 * context.resources.displayMetrics.density).toInt()
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, padding / 2)
            }
            content.addView(TextView(context).apply {
                text = "Адреса источников можно заменить, если сайт переехал. Укажите основной HTTPS-адрес без пути. После сохранения обновите главную страницу.\n\nОзвучки, качество и субтитры зависят от выбранного источника."
            })
            val labels = mapOf("zona" to "Zona — современный сайт zona.im", "rezka" to "HDRezka", "filmix" to "Filmix", "libria" to "AniLibria", "vost_web" to "AnimeVost — зеркало сайта", "vibe" to "KinoVibe")
            val inputs = defaults.mapValues { (key, _) ->
                content.addView(TextView(context).apply { text = labels.getValue(key) })
                EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setSingleLine(true)
                    setText(saved(key))
                    content.addView(this)
                }
            }
            val experimentalVibe = CheckBox(context).apply {
                text = "KinoVibe: доступность зависит от сети (потребуется перезапуск приложения)"
                isChecked = prefs.getBoolean("experimental_vibe", false)
                content.addView(this)
            }
            val experimentalRezka = CheckBox(context).apply {
                text = "HDRezka: нестабильный источник (потребуется перезапуск приложения)"
                isChecked = prefs.getBoolean("experimental_rezka", false)
                content.addView(this)
            }
            val experimental = CheckBox(context).apply {
                text = "Filmix: экспериментальный источник (потребуется перезапуск приложения)"
                isChecked = prefs.getBoolean("experimental_filmix", false)
                content.addView(this)
            }
            val scroll = ScrollView(context).apply { addView(content) }
            val dialog = AlertDialog.Builder(context).setTitle("Кадроград · KG-RU-2026")
                .setView(scroll).setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null)
                .setNeutralButton("Сбросить адреса") { _, _ ->
                    prefs.edit().clear().apply()
                    rezka.changeMirror(defaults.getValue("rezka"))
                    filmix.mainUrl = defaults.getValue("filmix")
                    libria.mainUrl = defaults.getValue("libria")
                    vost.mainUrl = defaults.getValue("vost_web")
                    vibe.mainUrl = defaults.getValue("vibe")
                    zona.mainUrl = defaults.getValue("zona")
                    Toast.makeText(context, "Адреса восстановлены. Перезапустите приложение для смены набора источников", Toast.LENGTH_LONG).show()
                }.create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val values = inputs.mapValues { StreamTools.origin(it.value.text.toString()) }
                    if (values.values.any { it == null }) {
                        inputs.forEach { (key, input) -> if (values[key] == null) input.error = "Нужен HTTPS-адрес, например https://example.org" }
                    } else {
                        val editor = prefs.edit()
                        editor.putBoolean("experimental_vibe", experimentalVibe.isChecked)
                        editor.putBoolean("experimental_filmix", experimental.isChecked)
                        editor.putBoolean("experimental_rezka", experimentalRezka.isChecked)
                        values.forEach { (key, value) -> editor.putString(key, value) }
                        editor.apply()
                        rezka.changeMirror(values.getValue("rezka")!!)
                        filmix.mainUrl = values.getValue("filmix")!!
                        libria.mainUrl = values.getValue("libria")!!
                        vost.mainUrl = values.getValue("vost_web")!!
                        vibe.mainUrl = values.getValue("vibe")!!
                        zona.mainUrl = values.getValue("zona")!!
                        dialog.dismiss()
                    }
                }
            }
            dialog.show()
        }
    }
}
