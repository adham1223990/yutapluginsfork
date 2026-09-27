package com.github.yutaplug.profileeffects

import kotlin.math.ceil

/** All positions share Discord's 450px design canvas, including offsets and smaller layers. */
internal object EffectDocument {
    fun surfaceHeight(effect: Product.Effect, width: Int, cardHeight: Int): Int {
        var bottom = 1.0
        for (layer in effect.layers) bottom = maxOf(bottom, layer.y.toDouble() + layer.height)
        return ceil(bottom * width.coerceAtLeast(1) / EFFECT_WIDTH)
            .coerceIn(1.0, minOf(MAX_SURFACE_SIZE, cardHeight.coerceAtLeast(1)).toDouble())
            .toInt()
    }

    fun html(effect: Product.Effect): String = buildString {
        append(
            """<!doctype html><html><head>
                <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https://cdn.discordapp.com https://media.discordapp.net; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
                <style>
                html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:transparent}
                #canvas{position:absolute;left:0;top:0;width:450px;transform-origin:0 0;pointer-events:none}
                .effect{position:absolute;display:block;visibility:hidden}
                #fallback{position:absolute;left:0;top:0;width:100%;height:auto;pointer-events:none}
                </style></head><body>
            """,
        )
        effect.source?.let { append("<img id=\"fallback\" src=\"${escape(it)}\" aria-hidden=\"true\">") }
        append("<div id=\"canvas\">")
        for (layer in effect.layers) {
            append("<img class=\"effect\" data-src=\"${escape(layer.source)}\"")
            append(" data-start=\"${layer.start}\" data-duration=\"${layer.duration}\"")
            append(" data-loop=\"${layer.loop}\" data-delay=\"${layer.loopDelay}\"")
            append(" style=\"left:${layer.x}px;top:${layer.y}px;width:${layer.width}px;")
            append("height:${layer.height}px;z-index:${layer.zIndex}\" aria-hidden=\"true\">")
        }
        append(
            """</div><script>
                (function(){
                    var canvas=document.getElementById('canvas'),timers=[],active=false,ready=false,loaded=0;
                    var layers=Array.prototype.slice.call(canvas.children);
                    function resize(){canvas.style.transform='scale('+(document.documentElement.clientWidth/450)+')';}
                    window.addEventListener('resize',resize);resize();
                    function later(task,delay){
                        var id=window.setTimeout(function(){
                            var index=timers.indexOf(id);if(index>=0)timers.splice(index,1);task();
                        },delay);timers.push(id);
                    }
                    function reset(index){
                        var old=layers[index],img=old.cloneNode(false);
                        img.removeAttribute('src');img.style.visibility='hidden';
                        old.parentNode.replaceChild(img,old);layers[index]=img;return img;
                    }
                    window.pauseEffects=function(){
                        active=false;timers.forEach(function(id){window.clearTimeout(id);});timers=[];
                        for(var i=0;i<layers.length;i++)reset(i);
                    };
                    function play(index){
                        if(!active)return;
                        var img=reset(index),duration=Number(img.getAttribute('data-duration'))||0;
                        img.src=img.getAttribute('data-src');img.style.visibility='visible';
                        if(duration>0){later(function(){
                            img.removeAttribute('src');img.style.visibility='hidden';
                            if(img.getAttribute('data-loop')==='true'){
                                later(function(){play(index);},Number(img.getAttribute('data-delay'))||0);
                            }
                        },duration);}
                    }
                    function begin(){
                        if(!active||!ready||loaded===0)return;
                        var fallback=document.getElementById('fallback');if(fallback)fallback.style.display='none';
                        layers.forEach(function(img,index){later(function(){play(index);},Number(img.getAttribute('data-start'))||0);});
                    }
                    window.restartEffects=function(){window.pauseEffects();active=true;begin();};
                    var remaining=layers.length,preloads=[];
                    layers.forEach(function(img){
                        var preload=new Image(),settled=false;preloads.push(preload);
                        function done(ok){if(settled)return;settled=true;if(ok)loaded++;if(--remaining===0){ready=true;begin();}}
                        preload.onload=function(){done(true);};preload.onerror=function(){done(false);};
                        preload.src=img.getAttribute('data-src');
                    });
                })();
                </script></body></html>
            """,
        )
    }

    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&#39;")
}
