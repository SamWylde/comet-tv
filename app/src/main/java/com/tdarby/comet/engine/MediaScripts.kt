package com.tdarby.comet.engine

import android.view.KeyEvent

/**
 * JavaScript for the page side of remote media control. [frameAgent] is installed in every frame
 * and answers the app through a message object; [queryScript] and [commandScript] are the fallback
 * when the WebView can't do that, and reach the top page and same-origin frames only. Both build on
 * one shared core so an element is described and changed the same way. The reply format is
 * documented on [MediaWire].
 */
object MediaScripts {
    /** Describes elements ("it"), lists a document's players ("ls") and changes one ("go"). */
    private const val CORE = """
function ls(d){try{return Array.prototype.slice.call(d.querySelectorAll('video,audio'),0,40);}catch(e){return [];}}
function nm(x){return (typeof x==='number'&&isFinite(x)&&x>=0)?String(Math.round(x*100)/100):'-';}
function rg(v){var o=[];try{var s=v.seekable;for(var i=0;i<s.length&&i<3;i++){var a=s.start(i),b=s.end(i);
if(isFinite(a)&&isFinite(b)&&a>=0&&b>=a)o.push(Math.round(a*100)/100+':'+Math.round(b*100)/100);}}catch(e){}return o.join('/');}
function ar(v){try{if(v.tagName==='AUDIO')return 0;var w=v.ownerDocument.defaultView,c=w.getComputedStyle(v);
if(c.visibility==='hidden'||c.display==='none')return 0;var r=v.getBoundingClientRect();
var x=Math.min(r.right,w.innerWidth)-Math.max(r.left,0),y=Math.min(r.bottom,w.innerHeight)-Math.max(r.top,0);
return (x>0&&y>0)?Math.round(x*y):0;}catch(e){return 0;}}
function it(v,i){var d=v.duration;
var f=(v.paused?1:0)|(v.ended?2:0)|((v.muted||v.volume===0)?4:0)|(v.error?8:0)|(v.tagName==='AUDIO'?16:0)|(v.loop?32:0);
return [i,f,nm(v.currentTime),d===Infinity?'i':nm(d),nm(v.playbackRate),ar(v),v.readyState|0,rg(v)].join(',');}
function go(v,i,op,a,sync,done){var fin=false;function f(ok){if(fin)return;fin=true;done(ok,it(v,i));}
try{
if(op==='play'){if(!v.paused&&!v.ended){f(1);return;}var p=v.play();
if(p&&typeof p.then==='function'){if(sync){p.then(null,function(){});f(1);}
else{p.then(function(){f(1);},function(){f(0);});setTimeout(function(){f(!v.paused);},800);}}else f(1);}
else if(op==='pause'){v.pause();f(1);}
else if(op==='seek'){v.currentTime=a;f(1);}
else if(op==='rate'){v.playbackRate=a;f(Math.abs(v.playbackRate-a)<0.01);}
else if(op==='stop'){v.pause();try{v.currentTime=0;}catch(e){}f(1);}
else f(0);
}catch(e){f(0);}}
"""

    /**
     * The script every frame runs at document start. [bridgeName] is the object the WebView puts in
     * the frame; the script says hello once media exists and then only answers the app's requests.
     */
    fun frameAgent(bridgeName: String): String = "(function(){var N='$bridgeName';" + CORE + """
var h=null,said=false;
function post(s){try{h.postMessage(s);}catch(e){}}
function hello(){if(said||!h)return;if(ls(document).length===0)return;said=true;post('H');}
function onMsg(e){try{var m=e&&e.data;if(typeof m!=='string'||m.length>64)return;
var p=m.split(';'),c=p[0].charAt(0),id=p[0].slice(1);
if(c==='Q'){post('R'+id+ls(document).map(function(v,i){return ';'+it(v,i);}).join(''));}
else if(c==='C'){var i=parseInt(p[1],10),v=ls(document)[i];if(!v){post('A'+id+';0;');return;}
go(v,i,p[2],parseFloat(p[3]),false,function(ok,s){post('A'+id+';'+(ok?1:0)+';'+s);});}
}catch(x){}}
function init(){if(h)return;try{var o=window[N];if(o&&typeof o.postMessage==='function'){h=o;o.onmessage=onMsg;hello();}}catch(e){}}
init();
['DOMContentLoaded','load'].forEach(function(t){window.addEventListener(t,function(){init();hello();});});
['loadstart','loadedmetadata','play','playing'].forEach(function(t){document.addEventListener(t,function(){init();hello();},true);});
})();
"""

    private const val SAME_ORIGIN_WALK = """
function all(){var out=[];function walk(d,depth){var l=ls(d);for(var k=0;k<l.length&&out.length<40;k++)out.push(l[k]);
if(depth>=3)return;var f;try{f=d.querySelectorAll('iframe');}catch(e){return;}
for(var j=0;j<f.length&&j<16;j++){try{if(f[j].contentDocument)walk(f[j].contentDocument,depth+1);}catch(e){}}}
walk(document,0);return out;}
"""

    /** Fallback: lists the players of the top page and its same-origin frames. */
    fun queryScript(): String = "(function(){" + CORE + SAME_ORIGIN_WALK +
        "return 'R0'+all().map(function(v,i){return ';'+it(v,i);}).join('');})();"

    /** Fallback: applies [op] to player number [index] as numbered by [queryScript]. */
    fun commandScript(index: Int, op: MediaOp): String = "(function(){" + CORE + SAME_ORIGIN_WALK +
        "var r='A0;0;',v=all()[$index];" +
        "if(v)go(v,$index,'${MediaWire.opName(op)}',${MediaWire.number(op)},true," +
        "function(ok,s){r='A0;'+(ok?1:0)+';'+s;});return r;})();"

    /**
     * Probe used by connected physical-TV soak tests without changing playback: the first playing
     * player (else the first one) in the top page or a same-origin frame, or null when none.
     */
    const val PLAYBACK_PROBE =
        "(function(){var m=[];function c(d,n){try{m=m.concat(Array.from(d.querySelectorAll('video,audio')));" +
            "if(n<3)Array.from(d.querySelectorAll('iframe')).forEach(function(f){try{if(f.contentDocument)c(f.contentDocument,n+1);}catch(e){}});}catch(e){}}" +
            "c(document,0);var v=m.find(function(e){return !e.paused&&!e.ended;})||m[0];" +
            "return v?JSON.stringify({time:v.currentTime,paused:v.paused,ended:v.ended," +
            "error:v.error?v.error.code:0,ready:v.readyState}):null;})();"
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
