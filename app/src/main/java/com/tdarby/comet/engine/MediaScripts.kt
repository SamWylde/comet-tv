package com.tdarby.comet.engine

import android.view.KeyEvent

/** JavaScript kept in one testable place so every remote media action targets video and audio. */
object MediaScripts {
    fun forAction(action: MediaAction): String {
        val operation = when (action) {
            MediaAction.PLAY_PAUSE -> "if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}else v.pause();"
            MediaAction.PLAY -> "if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}"
            MediaAction.PAUSE -> "if(!v.paused)v.pause();"
            MediaAction.STOP -> "v.pause();try{v.currentTime=0;}catch(e){}"
            MediaAction.REWIND -> "try{v.currentTime=Math.max(0,v.currentTime-10);}catch(e){}"
            MediaAction.FORWARD -> "try{v.currentTime=v.currentTime+10;}catch(e){}"
        }
        // Media in same-origin iframes counts too; cross-origin frames throw and stay out of reach.
        return "(function(){var m=[];" +
            "function c(d){try{m=m.concat(Array.from(d.querySelectorAll('video,audio')));" +
            "Array.from(d.querySelectorAll('iframe')).forEach(function(f){" +
            "try{if(f.contentDocument)c(f.contentDocument);}catch(e){}});}catch(e){}}" +
            "c(document);" +
            "var v=m.find(function(e){return !e.paused&&!e.ended;})||m[0];" +
            "if(!v)return;$operation})();"
    }

    /** Probe used by connected physical-TV soak tests without changing playback. */
    const val PLAYBACK_PROBE =
        "(function(){var v=document.querySelector('video,audio');" +
            "return v?JSON.stringify({time:v.currentTime,paused:v.paused,ended:v.ended}):null;})();"
}

/** Remote media key -> action decision. */
object MediaKeys {
    fun isMediaKey(keyCode: Int): Boolean = baseAction(keyCode) != null

    /**
     * The action for a key press, or null for a non-media key. Discrete actions (play, pause,
     * toggle, stop) ignore key repeats so a held key doesn't flap; seeking keeps its repeats.
     */
    fun actionFor(keyCode: Int, repeatCount: Int): MediaAction? {
        val action = baseAction(keyCode) ?: return null
        val seeks = action == MediaAction.REWIND || action == MediaAction.FORWARD
        return if (repeatCount > 0 && !seeks) null else action
    }

    private fun baseAction(keyCode: Int): MediaAction? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> MediaAction.PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY -> MediaAction.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaAction.PAUSE
        KeyEvent.KEYCODE_MEDIA_STOP -> MediaAction.STOP
        KeyEvent.KEYCODE_MEDIA_REWIND -> MediaAction.REWIND
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> MediaAction.FORWARD
        else -> null
    }
}
