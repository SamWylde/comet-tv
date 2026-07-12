package com.tdarby.comet.engine

/** JavaScript kept in one testable place so every remote media action targets video and audio. */
object MediaScripts {
    fun forAction(action: MediaAction): String {
        val operation = when (action) {
            MediaAction.PLAY_PAUSE -> "if(v.paused)v.play();else v.pause();"
            MediaAction.STOP -> "v.pause();try{v.currentTime=0;}catch(e){}"
            MediaAction.REWIND -> "try{v.currentTime=Math.max(0,v.currentTime-10);}catch(e){}"
            MediaAction.FORWARD -> "try{v.currentTime=v.currentTime+10;}catch(e){}"
        }
        return "(function(){var m=Array.from(document.querySelectorAll('video,audio'));" +
            "var v=m.find(function(e){return !e.paused&&!e.ended;})||m[0];" +
            "if(!v)return;$operation})();"
    }

    /** Probe used by connected physical-TV soak tests without changing playback. */
    const val PLAYBACK_PROBE =
        "(function(){var v=document.querySelector('video,audio');" +
            "return v?JSON.stringify({time:v.currentTime,paused:v.paused,ended:v.ended}):null;})();"
}
