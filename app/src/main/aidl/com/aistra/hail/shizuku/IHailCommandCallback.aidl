package com.aistra.hail.shizuku;

oneway interface IHailCommandCallback {
    void onComplete(String requestId, int exitCode, String output, boolean timedOut, boolean truncated);
}
