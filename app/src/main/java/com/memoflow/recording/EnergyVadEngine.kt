package com.memoflow.recording
import com.memoflow.domain.*
import kotlin.math.sqrt
class EnergyVadEngine(private val threshold:Double=0.012):VadEngine {
 private var speech=false; private var startNs=0L
 override suspend fun process(frame:AudioFrame):List<AudioRange>{ val s=frame.pcm; var sum=0.0; var i=0; while(i+1<s.size){val v=((s[i].toInt() and 255) or (s[i+1].toInt() shl 8)).toShort().toInt()/32768.0;sum+=v*v;i+=2}; val rms=if(s.isEmpty())0.0 else sqrt(sum/(s.size/2)); val now=frame.timestampNs; val out=mutableListOf<AudioRange>(); if(rms>=threshold&&!speech){speech=true;startNs=now}; if(rms<threshold&&speech){speech=false; val st=((startNs/1_000_000)%600_000); val en=((now/1_000_000)%600_000); out+=AudioRange("active",st,en,confidence=(rms/threshold).coerceIn(0.0,1.0).toFloat(),modelId="energy-vad",modelVersion="1")}; return out }
 override suspend fun reset(){speech=false;startNs=0}
}
