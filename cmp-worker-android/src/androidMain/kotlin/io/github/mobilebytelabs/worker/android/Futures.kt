package io.github.mobilebytelabs.worker.android

import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// 只在完成监听器内读取结果，使用 API 24 已有的 CompletableFuture API。
internal fun <T> ListenableFuture<T>.asCompletableFuture(): CompletableFuture<T> {
    val completion = CompletableFuture<T>()
    completion.whenComplete { _, _ ->
        if (completion.isCancelled) cancel(false)
    }
    addListener(
        {
            try {
                if (isCancelled) {
                    completion.cancel(false)
                } else {
                    completion.complete(get())
                }
            } catch (error: CancellationException) {
                completion.cancel(false)
            } catch (error: ExecutionException) {
                completion.completeExceptionally(error.cause ?: error)
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
            }
        },
        { command -> command.run() },
    )
    return completion
}

internal suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener(
        {
            try {
                if (isCancelled) {
                    cont.cancel()
                } else {
                    cont.resume(get())
                }
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        },
        { command -> command.run() },
    )
    cont.invokeOnCancellation { cancel(false) }
}
