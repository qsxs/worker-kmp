package io.github.mobilebytelabs.worker.ohos.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import platform.posix.EACCES
import platform.posix.EAGAIN
import platform.posix.F_OFD_SETLK
import platform.posix.F_WRLCK
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDWR
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.flock
import platform.posix.open
import kotlin.uuid.Uuid

/** 所有执行进程传入同一个应用私有 filesDir。锁文件保留原 inode，结束时只关闭描述符。 */
@OptIn(ExperimentalForeignApi::class)
internal class OhosExecutionLease(directory: Path, lockDirectory: String) {
    private val lockPath = directory / lockDirectory

    suspend fun <T> withLock(id: Uuid, generation: Long, action: suspend () -> T): T? = withContext(Dispatchers.IO) {
        require(generation >= 0) { "Work generation must be non-negative" }
        FileSystem.SYSTEM.createDirectories(lockPath)
        val path = lockPath / "$id-$generation.lock"
        val fd = open(path.toString(), O_RDWR or O_CREAT or O_CLOEXEC or O_NOFOLLOW, 384u)
        if (fd < 0) {
            val openErrno = errno
            error("Could not open the execution lease (errno=$openErrno)")
        }

        var primary: Throwable? = null
        try {
            if (tryLock(fd)) action() else null
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            // close 失败也不重试，原描述符可能已被内核释放并分配给其他调用方。
            if (close(fd) != 0) {
                val closeErrno = errno
                val failure = IllegalStateException("Could not close the execution lease (errno=$closeErrno)")
                if (primary == null) throw failure
                primary.addSuppressed(failure)
            }
        }
    }

    suspend fun <T : Any> withLockUntil(
        id: Uuid,
        generation: Long,
        shouldWait: suspend () -> Boolean,
        action: suspend () -> T,
    ): T? {
        while (shouldWait()) {
            withLock(id, generation, action)?.let { return it }
            delay(25L)
        }
        return null
    }

    private fun tryLock(fd: Int): Boolean = memScoped {
        val request =
            cValue<flock> {
                l_type = F_WRLCK.convert()
                l_whence = SEEK_SET.convert()
                l_start = 0
                l_len = 1
                l_pid = 0
            }
        if (fcntl(fd, F_OFD_SETLK, request.ptr) == 0) return@memScoped true
        val lockErrno = errno
        when (lockErrno) {
            EAGAIN, EACCES -> false
            else -> error("Could not acquire the execution lease (errno=$lockErrno)")
        }
    }
}

internal fun nameExecutionLeases(directory: Path): OhosExecutionLease = OhosExecutionLease(directory, "name-leases")
