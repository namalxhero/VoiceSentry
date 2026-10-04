package com.nipuna.voicesentry

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "core": sends the owner's (already voice-verified) audio to Gemini, which understands Sinhala,
 * decides what to do, calls phone tools, and returns a short Sinhala answer to be spoken.
 */
object Brain {
    data class Reply(val heard: String, val say: String, val ok: Boolean, val more: Boolean = true)

    private class ApiError(val code: Int, msg: String) : Exception(msg)

    private val history = ArrayList<JSONObject>()
    private var lastAt = 0L
    private var turn = 0

    // Dangerous commands only run after the owner confirms in a LATER voice message (enforced here in code).
    private var pendingCmd: String? = null
    private var pendingTurn = -1
    private var pendingAt = 0L

    private val ACTIONS = mapOf(
        "unlock" to "unlock", "lock" to "lock",
        "flashlight_on" to "flashlight on", "flashlight_off" to "flashlight off",
        "wifi_on" to "wifi on", "wifi_off" to "wifi off",
        "bluetooth_on" to "bluetooth on", "bluetooth_off" to "bluetooth off",
        "data_on" to "mobile data on", "data_off" to "mobile data off",
        "airplane_on" to "airplane mode on", "airplane_off" to "airplane mode off",
        "volume_up" to "volume up", "volume_down" to "volume down", "mute" to "mute",
        "brighter" to "brighter", "dimmer" to "dimmer", "screenshot" to "screenshot",
        "home" to "go home", "back" to "go back", "recents" to "recents",
        "play_pause" to "play", "next_track" to "next", "prev_track" to "previous",
    )

    // Never allowed: could brick/wipe the phone, remove the lock, or leak this app's PIN.
    private val BLOCK = Regex(
        "(\\bdd\\b|mkfs|factory.?reset|\\bwipe\\b|resetprop|locksettings|magisk|voicesentry|/data/data|shared_prefs|\\bsu\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val ROOT_RM = Regex(
        "\\brm\\s+-\\S*[rR]\\S*\\s+(/|/\\*|/data\\S*|/system\\S*|/vendor\\S*|/product\\S*|/storage/emulated/0/?|/sdcard/?|~)(\\s|$)",
    )

    // Allowed, but only after a spoken confirmation in the next message.
    private val DANGEROUS = Regex(
        "(\\breboot\\b|\\bpoweroff\\b|\\bshutdown\\b|svc\\s+power\\s+shutdown|pm\\s+(uninstall|clear|disable)|\\brm\\b|" +
            "force-stop|\\bpkill\\b|\\bkillall\\b|\\bkill\\b|settings\\s+put\\s+(secure|global)|" +
            "android\\.intent\\.action\\.CALL|sms_body|SENDTO|sendtext)",
        RegexOption.IGNORE_CASE,
    )

    private val TOOLS = JSONArray(
        """[{"functionDeclarations":[
{"name":"phone_action","description":"Do a common phone action instantly and reliably.",
 "parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING","enum":[
 "unlock","lock","flashlight_on","flashlight_off","wifi_on","wifi_off","bluetooth_on","bluetooth_off",
 "data_on","data_off","airplane_on","airplane_off","volume_up","volume_down","mute","brighter","dimmer",
 "screenshot","home","back","recents","play_pause","next_track","prev_track"]}},"required":["action"]}},
{"name":"open_app","description":"Open an installed app by its name, for example YouTube, WhatsApp, Camera, Settings.",
 "parameters":{"type":"OBJECT","properties":{"app_name":{"type":"STRING"}},"required":["app_name"]}},
{"name":"screen_ui","description":"Read what is on the screen right now: a list of texts and buttons with the x,y centre to tap. Use it to operate any app (find the Send button, tap it with shell: input tap X Y)."},
{"name":"shell","description":"Run any Android shell command as root and get its output. Use it for everything the other tools cannot do. Examples: power off = 'reboot -p'; restart = 'reboot'; recovery = 'reboot recovery'; bootloader = 'reboot bootloader'; uninstall app = 'pm uninstall PACKAGE' (find it with 'pm list packages | grep NAME'; system apps: 'pm uninstall -k --user 0 PACKAGE'); call = 'am start -a android.intent.action.CALL -d tel:NUMBER'; SMS = 'am start -a android.intent.action.SENDTO -d sms:NUMBER --es sms_body TEXT' then screen_ui and tap Send; tap = 'input tap X Y'; type English text = 'input text hello%sworld' (Sinhala cannot be typed this way); battery = 'dumpsys battery'. Dangerous commands answer NEEDS_CONFIRMATION first.",
 "parameters":{"type":"OBJECT","properties":{"command":{"type":"STRING"}},"required":["command"]}}
]}]""",
    )

    private fun system(): String {
        val now = SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return """You are the voice brain of the owner's rooted Samsung Galaxy A05 (Android 15), like a personal Jarvis with full control of the phone. The owner lives in Sri Lanka and speaks Sinhala (sometimes mixed with English words). Current time: $now.
You receive the owner's voice as audio. It may start with the wake word (for example "hello"); ignore the wake word.
You are a full conversational assistant: you can chat, answer questions, explain, translate and help with anything, and you can control the phone completely. Prefer the dedicated tools; use shell for anything else. You can chain many tool calls (use screen_ui to see the screen, then tap).
TALK TO THE OWNER IN REAL TIME. Everything you write is spoken aloud:
- Before every tool call, write ONE very short Sinhala sentence in the same message saying what you are doing right now (for example "YouTube open කරනවා."). It is spoken immediately.
- Tell the owner about everything that happens, including problems and what you will try next.
- When everything is finished, report the result in one short sentence and then ask what else to do, in Sinhala ("තව මොනවා හරි කරන්න ඕනේද?"), unless the owner is clearly finished.
Never reveal, read out or ask for the lock-screen PIN.
Dangerous commands (power off, reboot, recovery, uninstall, delete, calling, sending messages) are guarded: shell first answers NEEDS_CONFIRMATION. Then tell the owner in Sinhala exactly what you are about to do and ask them to confirm. Only if the owner says yes in their NEXT message, call the exact same command again. Never claim you did it before it ran.
If you could not understand the audio, ask the owner to repeat.
Reply in Sinhala (Sinhala script), short sentences, no markdown, no emoji, no lists. If the owner spoke English, reply in English.
Your FINAL message (the one without tool calls) must be exactly three lines:
HEARD: <what the owner said, in the language they used>
SAY: <your spoken reply>
MORE: yes or no
MORE is "no" only when the owner says they are finished (for example "ඉවරයි", "ඕනේ නෑ", "thanks", "bye"); then SAY a short goodbye. Otherwise MORE is "yes"."""
    }

    private fun wavBase64(f: FloatArray): String {
        val pcm = ByteArray(f.size * 2)
        for (i in f.indices) {
            val v = (f[i].coerceIn(-1f, 1f) * 32767f).toInt()
            pcm[2 * i] = (v and 0xff).toByte()
            pcm[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        val out = ByteArrayOutputStream()
        fun le32(x: Int) { for (s in 0..24 step 8) out.write((x shr s) and 0xff) }
        fun le16(x: Int) { out.write(x and 0xff); out.write((x shr 8) and 0xff) }
        out.write("RIFF".toByteArray()); le32(36 + pcm.size)
        out.write("WAVE".toByteArray()); out.write("fmt ".toByteArray())
        le32(16); le16(1); le16(1); le32(Audio.SR); le32(Audio.SR * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(pcm.size)
        out.write(pcm)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun post(p: Prefs, contents: JSONArray, thinkingOff: Boolean = true): JSONObject {
        val body = JSONObject()
        body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system()))))
        body.put("contents", contents)
        body.put("tools", TOOLS)
        val gen = JSONObject().put("temperature", 0.4)
        if (thinkingOff) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        body.put("generationConfig", gen)

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${p.geminiModel}:generateContent")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 45000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", p.geminiKey.trim())
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) return JSONObject(txt)
            if (code == 400 && thinkingOff) return post(p, contents, false)
            val msg = try { JSONObject(txt).getJSONObject("error").getString("message") } catch (_: Exception) { txt.take(160) }
            throw ApiError(code, msg)
        } finally {
            c.disconnect()
        }
    }

    private fun shell(cmd0: String): String {
        val cmd = cmd0.trim()
        if (cmd.isEmpty()) return "empty command"
        if (BLOCK.containsMatchIn(cmd) || ROOT_RM.containsMatchIn(cmd)) {
            return "REFUSED: this command is permanently blocked (it could wipe or brick the phone, remove the lock, or expose the PIN). Tell the owner to do it manually."
        }
        if (DANGEROUS.containsMatchIn(cmd)) {
            val key = cmd.replace(Regex("\\s+"), " ")
            val now = System.currentTimeMillis()
            val confirmed = pendingCmd == key && pendingTurn < turn && now - pendingAt < 90_000
            if (!confirmed) {
                pendingCmd = key
                pendingTurn = turn
                pendingAt = now
                return "NEEDS_CONFIRMATION: nothing was executed. Tell the owner in Sinhala what you are about to do and ask them to confirm. If they say yes in their next message, call this exact same command again."
            }
            pendingCmd = null
        }
        val (code, out) = RootShell.run(cmd, 20000)
        val o = if (out.length > 1500) out.take(1500) + "…" else out
        return "exit=$code\n$o"
    }

    private fun unescape(s: String) = s
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&#10;", " ").replace("&amp;", "&")

    private fun screenUi(): String {
        val (code, xml) = RootShell.run(
            "uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml", 15000,
        )
        if (!xml.contains("<node")) return "Could not read the screen (code $code). The screen may be off or locked."
        val sb = StringBuilder()
        var count = 0
        for (m in Regex("<node [^>]*>").findAll(xml)) {
            val s = m.value
            fun a(n: String) = Regex(n + "=\"([^\"]*)\"").find(s)?.groupValues?.get(1) ?: ""
            val text = unescape(a("text"))
            val desc = unescape(a("content-desc"))
            val click = a("clickable") == "true"
            if (text.isEmpty() && desc.isEmpty() && !click) continue
            val b = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").find(a("bounds")) ?: continue
            val cx = (b.groupValues[1].toInt() + b.groupValues[3].toInt()) / 2
            val cy = (b.groupValues[2].toInt() + b.groupValues[4].toInt()) / 2
            val label = listOf(text, desc).filter { it.isNotEmpty() }.joinToString(" / ").ifEmpty { a("resource-id").substringAfterLast('/') }
            sb.append(if (click) "[btn] " else "").append(label.take(60)).append(" @ ").append(cx).append(',').append(cy).append('\n')
            if (++count >= 60 || sb.length > 3500) break
        }
        return if (sb.isEmpty()) "Screen has no readable elements." else sb.toString()
    }

    private fun runTool(ctx: Context, p: Prefs, name: String, args: JSONObject, score: Float): String {
        return when (name) {
            "phone_action" -> {
                val phrase = ACTIONS[args.optString("action")] ?: return "unknown action"
                val r = Commands.execute(ctx, p, phrase, score)
                (if (r.ok) "OK: " else "FAILED: ") + r.label
            }
            "open_app" -> {
                val r = Commands.execute(ctx, p, "open " + args.optString("app_name"), score)
                (if (r.ok) "OK: " else "FAILED: ") + r.label
            }
            "screen_ui" -> screenUi()
            "shell" -> shell(args.optString("command"))
            else -> "unknown tool"
        }
    }

    fun ask(ctx: Context, p: Prefs, audio: FloatArray, score: Float, onSay: (String) -> Unit = {}): Reply {
        val now = System.currentTimeMillis()
        if (now - lastAt > 5 * 60_000) history.clear()
        lastAt = now
        turn++

        val contents = JSONArray()
        for (h in history) contents.put(h)
        contents.put(
            JSONObject().put("role", "user").put(
                "parts",
                JSONArray()
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", wavBase64(audio))))
                    .put(JSONObject().put("text", "The phone owner just said this.")),
            ),
        )

        var finalText = ""
        try {
            for (round in 0 until 10) {
                val resp = post(p, contents)
                val cand = resp.optJSONArray("candidates")?.optJSONObject(0)
                val content = cand?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts == null) {
                    val why = cand?.optString("finishReason") ?: resp.optJSONObject("promptFeedback")?.optString("blockReason") ?: "empty"
                    return Reply("(no answer: $why)", "", false)
                }
                val calls = ArrayList<JSONObject>()
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.has("functionCall")) calls.add(part.getJSONObject("functionCall"))
                    else if (part.has("text") && !part.optBoolean("thought", false)) sb.append(part.getString("text"))
                }
                if (calls.isEmpty()) { finalText = sb.toString(); break }

                // Real-time narration: speak what is about to happen before running the tools.
                val narration = sb.toString().trim()
                if (narration.isNotEmpty()) onSay(narration)

                contents.put(content)
                val responses = JSONArray()
                for (c in calls) {
                    val name = c.getString("name")
                    val out = runTool(ctx, p, name, c.optJSONObject("args") ?: JSONObject(), score)
                    responses.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject().put("name", name).put("response", JSONObject().put("result", out)),
                        ),
                    )
                }
                contents.put(JSONObject().put("role", "user").put("parts", responses))
            }
        } catch (e: ApiError) {
            return Reply("Gemini error ${e.code}: ${e.message}", "", false)
        } catch (e: IOException) {
            return Reply("(offline)", "ඉන්ටර්නෙට් සම්බන්ධතාවයක් නැහැ.", false)
        } catch (e: Exception) {
            return Reply("Error: ${e.message}", "", false)
        }

        val m = Regex(
            "HEARD:\\s*(.*?)\\s*SAY:\\s*(.*?)\\s*(?:MORE:\\s*(\\w+))?\\s*$",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).find(finalText.trim())
        val heard = m?.groupValues?.get(1)?.trim() ?: ""
        val say = (m?.groupValues?.get(2) ?: finalText).trim()
        val more = !(m?.groupValues?.get(3) ?: "yes").equals("no", ignoreCase = true)

        history.add(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", heard.ifEmpty { "(voice message)" }))))
        history.add(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", say.ifEmpty { "ok" }))))
        while (history.size > 12) history.removeAt(0)

        return Reply(heard.ifEmpty { "(voice)" }, say, true, more)
    }
}
