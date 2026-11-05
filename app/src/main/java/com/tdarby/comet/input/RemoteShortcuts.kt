package com.tdarby.comet.input

import android.view.KeyEvent
import com.tdarby.comet.R
import com.tdarby.comet.engine.MediaKeys

/**
 * Things a remote-button shortcut can do. To add one: add an entry here (with its string) and one
 * line in BrowserActivity.runRemoteAction. [id] is what is stored, so never rename it.
 */
enum class RemoteAction(val id: String, val labelRes: Int) {
    MENU("menu", R.string.action_menu),
    TABS("tabs", R.string.menu_tabs),
    HOME("home", R.string.menu_home),
    BOOKMARKS("bookmarks", R.string.menu_bookmarks),
    HISTORY("history", R.string.menu_history),
    DOWNLOADS("downloads", R.string.menu_downloads),
    NEW_TAB("new_tab", R.string.menu_new_tab),
    CLOSE_TAB("close_tab", R.string.menu_close_tab),
    RELOAD("reload", R.string.action_reload),
    VOICE_SEARCH("voice", R.string.menu_voice),
    TOGGLE_CURSOR("toggle_cursor", R.string.remote_action_toggle_cursor),
    ZOOM_IN("zoom_in", R.string.menu_zoom_in),
    ZOOM_OUT("zoom_out", R.string.menu_zoom_out),
    BACK("back", R.string.action_back),
    FORWARD("forward", R.string.action_forward),
    SETTINGS("settings", R.string.settings_title),
    HELP("help", R.string.menu_remote_help),
    THIS_SITE("this_site", R.string.menu_this_site),
    SEND_FROM_PHONE("send_from_phone", R.string.menu_send_from_phone),
    LINK_OPTIONS("link_options", R.string.menu_link_options),
    DRAG_MODE("drag_mode", R.string.menu_drag_mode),
    REOPEN_CLOSED_TAB("reopen_closed_tab", R.string.menu_reopen_closed_tab);

    companion object {
        fun fromId(id: String): RemoteAction? = entries.firstOrNull { it.id == id }
    }
}

/** Remote key -> action mapping with validation and JSON persistence. Immutable. */
class RemoteShortcuts private constructor(private val map: Map<Int, RemoteAction>) {

    val isEmpty: Boolean get() = map.isEmpty()

    /** Assignments sorted by key code. */
    val entries: List<Pair<Int, RemoteAction>> get() = map.toSortedMap().map { it.key to it.value }

    fun actionFor(keyCode: Int): RemoteAction? = map[keyCode]

    /** Assign (or replace) a key. Returns null if the key can't be used. */
    fun with(keyCode: Int, action: RemoteAction): RemoteShortcuts? =
        if (isAssignable(keyCode)) RemoteShortcuts(map + (keyCode to action)) else null

    fun without(keyCode: Int): RemoteShortcuts = RemoteShortcuts(map - keyCode)

    /** `{"183":"menu","184":"tabs"}`. Hand-built so the class stays testable on the plain JVM. */
    fun toJson(): String = entries.joinToString(",", "{", "}") { (key, action) ->
        "\"$key\":\"${action.id}\""
    }

    companion object {
        val EMPTY = RemoteShortcuts(emptyMap())

        private val NEVER = setOf(
            KeyEvent.KEYCODE_UNKNOWN,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE
        )

        /** Navigation, system and media keys keep their meaning; MENU and SEARCH may be reassigned. */
        fun isAssignable(keyCode: Int): Boolean =
            keyCode !in NEVER && !MediaKeys.isMediaKey(keyCode)

        private val PAIR = Regex("\"(\\d+)\"\\s*:\\s*\"([a-z_]+)\"")

        /** Tolerant: unknown actions and unusable keys are dropped, garbage gives an empty set. */
        fun fromJson(json: String?): RemoteShortcuts {
            if (json.isNullOrBlank()) return EMPTY
            val map = HashMap<Int, RemoteAction>()
            PAIR.findAll(json).forEach { m ->
                val key = m.groupValues[1].toIntOrNull() ?: return@forEach
                val action = RemoteAction.fromId(m.groupValues[2]) ?: return@forEach
                if (isAssignable(key)) map[key] = action
            }
            return RemoteShortcuts(map)
        }
    }
}
