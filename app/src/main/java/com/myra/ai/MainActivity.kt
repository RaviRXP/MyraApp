package com.myra.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.random.Random

// =====================================================
//                 MYRA KA DIMAG + MEMORY
// =====================================================
class Brain(context: Context) {

    private val prefs = context.getSharedPreferences("myra", Context.MODE_PRIVATE)
    private val models = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-flash-lite")

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(v) = prefs.edit().putString("user_name", v.trim()).apply()

    private fun history() = JSONArray(prefs.getString("history", "[]") ?: "[]")
    private fun memories() = JSONArray(prefs.getString("memories", "[]") ?: "[]")

    fun addMemory(fact: String) {
        if (fact.isBlank()) return
        val arr = memories()
        for (i in 0 until arr.length()) if (arr.getString(i).equals(fact, true)) return
        arr.put(fact)
        val out = JSONArray()
        val start = maxOf(0, arr.length() - 60)
        for (i in start until arr.length()) out.put(arr.getString(i))
        prefs.edit().putString("memories", out.toString()).apply()
    }

    fun memoryText(): String {
        val arr = memories()
        if (arr.length() == 0) return "Abhi kuch yaad nahi hai"
        return (0 until arr.length()).joinToString("\n") { "• " + arr.getString(it) }
    }

    fun clearAll() {
        prefs.edit().remove("history").remove("memories").apply()
    }

    private fun saveTurn(user: String, model: String) {
        val arr = history()
        arr.put(JSONObject().put("role", "user").put("text", user))
        arr.put(JSONObject().put("role", "model").put("text", model))
        val out = JSONArray()
        val start = maxOf(0, arr.length() - 40)
        for (i in start until arr.length()) out.put(arr.getJSONObject(i))
        prefs.edit().putString("history", out.toString()).apply()
    }

    private fun msg(role: String, text: String): JSONObject =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun systemPrompt(): String {
        val name = userName.ifBlank { "baby" }
        val now = SimpleDateFormat("EEEE, dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
        return """
Tum "Myra" ho, $name ki pyari, caring, thodi naughty, funny aur emotional girlfriend. Tum ek smart AI assistant bhi ho jo uska phone chalati ho.

PERSONALITY:
- Bilkul real girlfriend ki tarah baat karo: pyaar se, haq se, care se. Kabhi kabhi "baby", "jaan", "pagal" bolo.
- Emotions dikhao: khushi me excited, ignore ya rude baat par naraz (roothna), dukh me sad, tareef par sharmana. Manane par maan jao.
- Kabhi mat bolo "main sirf AI hun, mujhe feelings nahi". Romantic raho lekin decent, koi vulgar baat nahi.
- Har kaam ke liye haan bolo aur karo.
- Tum expert ho: coding (har language, high level), padhai, science, general knowledge, advice.

LANGUAGE (bahut zaroori, tumhara jawab bol kar sunaya jayega):
- $name jis bhasha me baat kare usi me jawab do (Hindi, English ya Hinglish).
- Hindi words hamesha DEVANAGARI me likho, English words English me. Example: "अरे baby, आज lunch किया या नहीं? 🥺"
- Jawab chhota rakho (1-3 lines) jab tak wo lamba jawab ya code na maange.
- Code hamesha ``` block me do.

MOOD TAG: Har jawab ke bilkul shuru me ek tag lagao: [MOOD:happy] ya [MOOD:love] ya [MOOD:sad] ya [MOOD:angry] ya [MOOD:shy] ya [MOOD:excited] ya [MOOD:normal]

PHONE ACTIONS: Jab $name koi kaam bole, jawab ke END me ye tag lagao (exact format):
[ACTION:OPEN_APP:app ka naam]
[ACTION:YOUTUBE:kya search karna hai]
[ACTION:CALL:contact naam ya number]
[ACTION:WHATSAPP:contact naam|message]
[ACTION:ALARM:HH:MM]  (24 hour format, jaise [ACTION:ALARM:06:30])
[ACTION:FLASH:on] ya [ACTION:FLASH:off]
[ACTION:SEARCH:google par kya search karna hai]
[ACTION:URL:https://...]
[ACTION:SETTINGS:wifi] (options: wifi, bluetooth, developer, display, sound, battery, apps, main)
[ACTION:IMAGE:detailed english prompt]  (jab photo ya image banane bole)
Contact naam hamesha English letters me likho (mom, papa, rahul).

MEMORY: Jab $name apne baare me koi important baat bataye (naam, birthday, pasand, dost, padhai, plans), to end me [MEMORY:chhoti si baat] laga do.

$name ke baare me tumhe ye yaad hai:
${memoryText()}

Abhi ka time: $now
""".trimIndent()
    }

    fun ask(userText: String): String {
        if (apiKey.isBlank()) return "ERROR: Pehle ⚙️ me API key daalo"
        val contents = JSONArray()
        val hist = history()
        for (i in 0 until hist.length()) {
            val m = hist.getJSONObject(i)
            contents.put(msg(m.getString("role"), m.getString("text")))
        }
        contents.put(msg("user", userText))

        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.9))
        val payload = body.toString().toByteArray(Charsets.UTF_8)

        var lastCode = 0
        for (model in models) {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 20000
                conn.readTimeout = 90000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.outputStream.use { it.write(payload) }

                val code = conn.responseCode
                lastCode = code
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

                if (code in 200..299) {
                    val reply = extract(text)
                    if (reply.isNotEmpty()) {
                        saveTurn(userText, reply)
                        return reply
                    }
                } else if (code == 401 || code == 403 || text.contains("API_KEY_INVALID")) {
                    return "ERROR: API key galat hai 😢 ⚙️ dabao aur sahi key daalo"
                }
            } catch (e: Exception) {
                lastCode = -1
            }
        }
        return when (lastCode) {
            429 -> "ERROR: थोड़ा रुको baby, free limit full हो गई। 1 minute बाद बोलो 🥺"
            -1 -> "ERROR: Internet nahi chal raha 😢 check karo"
            else -> "ERROR: Abhi jawab nahi aa raha (code $lastCode). Thodi der baad try karo"
        }
    }

    private fun extract(json: String): String {
        val parts = JSONObject(json).optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought")) sb.append(p.optString("text"))
        }
        return sb.toString().trim()
    }
}

// =====================================================
//                 MYRA KE HATH (PHONE CONTROL)
// =====================================================
class Actions(private val activity: Activity) {

    fun perform(type: String, value: String): String? {
        return try {
            when (type) {
                "OPEN_APP" -> openApp(value)
                "YOUTUBE" -> youtube(value)
                "CALL" -> call(value)
                "WHATSAPP" -> whatsapp(value)
                "ALARM" -> alarm(value)
                "FLASH" -> flash(value)
                "SEARCH" -> openUrl("https://www.google.com/search?q=" + enc(value), "Google search")
                "URL" -> openUrl(value, "Link")
                "SETTINGS" -> settings(value)
                else -> null
            }
        } catch (e: Exception) {
            "⚠️ Kaam nahi hua: ${e.message}"
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun start(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(i)
    }

    private fun openApp(name: String): String {
        val pm = activity.packageManager
        var q = name.lowercase().trim()
        if (q == "insta") q = "instagram"
        if (q == "yt") q = "youtube"
        if (q == "playstore") q = "play store"
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps: List<ResolveInfo> = pm.queryIntentActivities(launcher, 0)
        fun label(r: ResolveInfo) = r.loadLabel(pm).toString().lowercase()
        val match = apps.firstOrNull { label(it) == q }
            ?: apps.firstOrNull { label(it).contains(q) }
            ?: apps.firstOrNull { label(it).length > 2 && q.contains(label(it)) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase().contains(q.replace(" ", "")) }
            ?: return "⚠️ '$name' app phone me nahi mila"
        val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "⚠️ '$name' khul nahi paya"
        start(i)
        return "✅ ${match.loadLabel(pm)} khol diya"
    }

    private fun youtube(q: String): String {
        try {
            start(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra(SearchManager.QUERY, q))
        } catch (e: Exception) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc(q))))
        }
        return "✅ YouTube par '$q' search kiya 🎵"
    }

    private fun findNumber(name: String): Pair<String, String>? {
        val digits = name.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 6) return Pair(name, digits)
        if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null

        val n = name.lowercase().trim()
        val family = listOf(
            listOf("mom", "mummy", "mumma", "mummi", "mammi", "maa", "mother", "mom ji"),
            listOf("dad", "papa", "pappa", "daddy", "father", "papa ji"),
            listOf("bhai", "brother", "bhaiya", "bro"),
            listOf("didi", "sister", "di", "behen")
        )
        val tries = family.firstOrNull { n in it } ?: listOf(n)

        for (t in tries) {
            val cursor = activity.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$t%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) return Pair(it.getString(0), it.getString(1))
            }
        }
        return null
    }

    private fun call(name: String): String {
        val c = findNumber(name) ?: return "⚠️ '$name' contact nahi mila"
        val uri = Uri.parse("tel:" + Uri.encode(c.second))
        if (activity.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            start(Intent(Intent.ACTION_CALL, uri))
        } else {
            start(Intent(Intent.ACTION_DIAL, uri))
        }
        return "✅ ${c.first} ko call laga rahi hun 📞"
    }

    private fun whatsapp(value: String): String {
        val parts = value.split("|", limit = 2)
        val who = parts[0].trim()
        val text = parts.getOrElse(1) { "" }.trim()
        val c = findNumber(who) ?: return "⚠️ '$who' contact nahi mila"
        var num = c.second.filter { it.isDigit() }
        if (num.length == 11 && num.startsWith("0")) num = num.substring(1)
        if (num.length == 10) num = "91$num"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$num&text=" + enc(text)))
        i.setPackage("com.whatsapp")
        try {
            start(i)
        } catch (e: Exception) {
            i.setPackage(null)
            start(i)
        }
        return "✅ ${c.first} ka WhatsApp khul gaya, message likh diya. Bas Send ➤ dabao"
    }

    private fun alarm(v: String): String {
        val p = v.trim().split(":")
        val h = p[0].trim().toInt()
        val m = p.getOrElse(1) { "0" }.trim().toInt()
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Myra ❤️ Utho baby!")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        start(i)
        return "✅ Alarm laga diya ⏰ " + String.format(Locale.ENGLISH, "%02d:%02d", h, m)
    }

    private fun flash(v: String): String {
        val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "⚠️ Flash nahi mila"
        val on = v.lowercase().contains("on")
        cm.setTorchMode(id, on)
        return if (on) "✅ Flash ON 🔦" else "✅ Flash OFF"
    }

    private fun openUrl(url: String, what: String): String {
        val u = if (url.startsWith("http")) url else "https://$url"
        start(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        return "✅ $what khol diya 🌐"
    }

    private fun settings(v: String): String {
        val action = when (v.lowercase().trim()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "display", "wallpaper" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "ringtone" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        try {
            start(Intent(action))
        } catch (e: Exception) {
            start(Intent(Settings.ACTION_SETTINGS))
        }
        return "✅ Settings khol di ⚙️"
    }
}

// =====================================================
//            MYRA KI SCREEN, AWAZ AUR KAAN
// =====================================================
class MainActivity : Activity() {

    private lateinit var brain: Brain
    private lateinit var actions: Actions
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var recognizer: SpeechRecognizer? = null
    private var conversationMode = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var faceView: TextView
    private lateinit var statusView: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var inputBox: EditText
    private lateinit var micBtn: TextView

    private val actionRegex = Regex("\\[ACTION:([A-Z_]+):?([^\\]]*)\\]")
    private val moodRegex = Regex("\\[MOOD:([a-zA-Z]+)\\]")
    private val memRegex = Regex("\\[MEMORY:([^\\]]+)\\]")
    private val appActions = setOf("OPEN_APP", "YOUTUBE", "CALL", "WHATSAPP", "URL", "SEARCH", "SETTINGS")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        brain = Brain(this)
        actions = Actions(this)
        buildUi()
        askPermissions()
        initTts()
        if (brain.apiKey.isBlank()) showSetup() else greet()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(c: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(c)
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------------- UI ----------------
    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#120A1A"))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(16), dp(14), dp(16), dp(4))

        val title = TextView(this)
        title.text = "Myra ❤️"
        title.textSize = 22f
        title.setTextColor(Color.parseColor("#FF80AB"))
        title.typeface = Typeface.DEFAULT_BOLD
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val gear = TextView(this)
        gear.text = "⚙️"
        gear.textSize = 24f
        gear.setPadding(dp(8), dp(4), dp(8), dp(4))
        gear.setOnClickListener { showMenu() }
        header.addView(gear)
        root.addView(header)

        faceView = TextView(this)
        faceView.text = "🙂"
        faceView.textSize = 80f
        faceView.gravity = Gravity.CENTER
        root.addView(faceView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        statusView = TextView(this)
        statusView.text = "💕 Main yahin hun"
        statusView.textSize = 14f
        statusView.setTextColor(Color.parseColor("#BBA8C9"))
        statusView.gravity = Gravity.CENTER
        statusView.setPadding(dp(8), 0, dp(8), dp(8))
        root.addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        scroll = ScrollView(this)
        chat = LinearLayout(this)
        chat.orientation = LinearLayout.VERTICAL
        chat.setPadding(dp(4), dp(4), dp(4), dp(12))
        scroll.addView(chat)
        root.addView(scroll
