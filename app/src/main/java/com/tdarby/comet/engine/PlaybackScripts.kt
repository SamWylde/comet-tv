package com.tdarby.comet.engine

import com.tdarby.comet.data.PlaybackResumePolicy
import org.json.JSONObject
import org.json.JSONTokener

/** What the page's main video is doing right now. Times are in seconds. */
data class PlaybackSample(
    val videoCount: Int,
    val ready: Boolean,
    val live: Boolean,
    val paused: Boolean,
    val position: Double,
    val duration: Double
)

/**
 * Scripts for reading and seeking the page's main video for continue-watching. They reach the
 * main frame and same-origin iframes only; the main video is the largest visible one, playing
 * ones first.
 */
object PlaybackScripts {

    // Shared head: collects <video> elements and defines pick(filter).
    private const val FIND =
        "var all=[];function c(d,n){try{all=all.concat(Array.from(d.querySelectorAll('video')));" +
            "if(n<3)Array.from(d.querySelectorAll('iframe')).forEach(function(f){" +
            "try{if(f.contentDocument)c(f.contentDocument,n+1);}catch(e){}});}catch(e){}}" +
            "c(document,0);" +
            "function area(v){try{var r=v.getBoundingClientRect();return r.width*r.height;}catch(e){return 0;}}" +
            "function pick(ok){var best=null,bs=-1;all.forEach(function(v){if(!ok(v))return;" +
            "var a=area(v);if(a<=0)return;" +
            "var s=(!v.paused&&!v.ended?1e12:0)+(v.currentTime>0?1e9:0)+a;" +
            "if(s>bs){bs=s;best=v;}});return best;}"

    /** Reports the main video as JSON: {n, ready, live, paused, t, d}; n is how many videos exist. */
    val PROBE: String =
        "(function(){" + FIND +
            "var v=pick(function(){return true;});" +
            "if(!v)return JSON.stringify({n:all.length});" +
            "var live=v.duration===Infinity;" +
            "return JSON.stringify({n:all.length,ready:v.readyState>=1&&isFinite(v.duration)&&v.duration>0," +
            "live:live,paused:v.paused||v.ended,t:v.currentTime,d:isFinite(v.duration)?v.duration:-1});})();"

    /** Seeks the largest video whose length is within 2 s of [expectedDuration]. Returns true if it did. */
    fun seek(positionSeconds: Double, expectedDuration: Double): String =
        "(function(){" + FIND +
            "var v=pick(function(e){return isFinite(e.duration)&&Math.abs(e.duration-" + num(expectedDuration) +
            ")<=" + PlaybackResumePolicy.MATCH_TOLERANCE_SECONDS + ";});" +
            "if(!v)return false;try{v.currentTime=" + num(positionSeconds) + ";return true;}" +
            "catch(e){return false;}})();"

    private fun num(value: Double): String = if (value.isFinite()) value.toString() else "0"

    /** Parses the raw `evaluateJavascript` result of [PROBE]; null when it isn't a probe answer. */
    fun parse(raw: String?): PlaybackSample? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            val text = JSONTokener(raw).nextValue() as? String ?: return null
            val o = JSONObject(text)
            PlaybackSample(
                videoCount = o.optInt("n", 0),
                ready = o.optBoolean("ready", false),
                live = o.optBoolean("live", false),
                paused = o.optBoolean("paused", true),
                position = o.optDouble("t", 0.0),
                duration = o.optDouble("d", -1.0)
            )
        }.getOrNull()
    }
}
