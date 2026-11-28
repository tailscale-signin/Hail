package com.aistra.hail.shizuku;

import com.aistra.hail.shizuku.IHailCommandCallback;

interface IHailCommandService {
    oneway void execute(String requestId, in String[] argv, long deadlineElapsedRealtime,
        IHailCommandCallback callback) = 1;
    oneway void cancel(String requestId) = 2;
    // Shizuku's reserved destroy transaction is FIRST_CALL_TRANSACTION + 16777114.
    oneway void destroy() = 16777114;
}
