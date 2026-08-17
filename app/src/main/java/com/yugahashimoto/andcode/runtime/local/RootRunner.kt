package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.Socket

data class RootResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

object RootRunner {
    private const val MEM_SERVER_PATH = "/data/local/tmp/mem_server.sh"
    private const val MEM_SERVER_LOG = "/data/local/tmp/mem_server.log"
    private const val MEM_SERVER_PORT = 8088

    private val SU_PATHS =
        listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "su",
        )

    fun isRootAvailable(): Boolean {
        for (path in SU_PATHS) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf(path, "-c", "id"))
                val exit = proc.waitFor()
                if (exit == 0) {
                    val out = proc.inputStream.bufferedReader().readText()
                    if (out.contains("uid=0")) return true
                }
            } catch (_: Throwable) {
                continue
            }
        }
        return false
    }

    fun getRootType(): String {
        if (File("/data/adb/ksu").exists()) return "KernelSU"
        if (File("/data/adb/ap").exists()) return "APatch"
        if (File("/data/adb/magisk").exists() || File("/sbin/.magisk").exists()) return "Magisk"
        if (isRootAvailable()) return "Root (su)"
        return "Not Rooted"
    }

    suspend fun runAsRoot(
        command: String,
        timeoutMs: Long = 15000L,
    ): RootResult =
        withContext(Dispatchers.IO) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val stdoutFuture =
                    kotlinx.coroutines.async {
                        proc.inputStream.bufferedReader().use { it.readText() }
                    }
                val stderrFuture =
                    kotlinx.coroutines.async {
                        proc.errorStream.bufferedReader().use { it.readText() }
                    }
                val exitCode = proc.waitFor()
                val stdout = stdoutFuture.await()
                val stderr = stderrFuture.await()
                RootResult(
                    success = exitCode == 0,
                    exitCode = exitCode,
                    stdout = stdout.trim(),
                    stderr = stderr.trim(),
                )
            } catch (t: Throwable) {
                RootResult(
                    success = false,
                    exitCode = -1,
                    stdout = "",
                    stderr = t.message ?: "Execution failed",
                )
            }
        }

    suspend fun deployMemServer(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // 1. Extract asset to app private storage first
                val tempFile = File(context.cacheDir, "mem_server.sh")
                context.assets.open("bin/arm64-v8a/mem_server.sh").use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile.setExecutable(true, false)

                // 2. Copy to /data/local/tmp/mem_server.sh and chmod 777 via root
                val copyCmd = "cp -f '${tempFile.absolutePath}' '$MEM_SERVER_PATH' && chmod 777 '$MEM_SERVER_PATH'"
                val res = runAsRoot(copyCmd)
                tempFile.delete()
                res.success
            } catch (t: Throwable) {
                false
            }
        }

    suspend fun isMemServerRunning(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket("127.0.0.1", MEM_SERVER_PORT).use { socket ->
                    socket.soTimeout = 800
                    socket.getOutputStream().write("ping\n".toByteArray())
                    val buffer = ByteArray(128)
                    val read = socket.getInputStream().read(buffer)
                    read > 0
                }
            } catch (_: Throwable) {
                false
            }
        }

    suspend fun startMemServer(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            if (isMemServerRunning()) return@withContext true
            deployMemServer(context)
            val launchCmd = "nohup $MEM_SERVER_PATH > $MEM_SERVER_LOG 2>&1 &"
            runAsRoot(launchCmd)
            kotlinx.coroutines.delay(300)
            isMemServerRunning()
        }

    suspend fun stopMemServer(): Boolean =
        withContext(Dispatchers.IO) {
            runAsRoot("pkill -f mem_server.sh").success
        }
}
