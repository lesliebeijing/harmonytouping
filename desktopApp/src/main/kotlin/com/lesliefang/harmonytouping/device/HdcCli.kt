package com.lesliefang.harmonytouping.device

import java.io.File
import java.util.concurrent.TimeUnit

/** 一台可通过 hdc 访问的鸿蒙设备 */
data class HosDevice(val ip: String = LOCAL_HOST, val sn: String) {
    val isRemote: Boolean get() = ip != LOCAL_HOST

    override fun toString(): String = if (isRemote) "$ip - $sn" else sn

    fun isOnline(): Boolean {
        if (sn.isEmpty()) return false
        val result = HdcCli.exec("-s $ip:${HdcCli.HDC_PORT} list targets", 10)
        return result.contains(sn)
    }

    companion object {
        const val LOCAL_HOST = "127.0.0.1"
    }
}

/**
 * hdc 命令行封装：设备发现、shell 执行。
 * 对应官方 utils/HdcUtil + utils/ProcessExecutor。
 */
object HdcCli {
    const val HDC_PORT = 8710

    @Volatile
    var hdcPath: String = System.getProperty("hdc.path")?.takeIf { it.isNotBlank() } ?: "hdc"

    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    /** 执行 `hdc <args>`，超时单位秒，失败返回空串 */
    fun exec(args: String, timeoutSec: Long = 10): String {
        return try {
            val parts = if (isWindows) {
                arrayOf("cmd", "/c", "$hdcPath $args")
            } else {
                arrayOf("sh", "-c", "$hdcPath $args")
            }
            val process = ProcessBuilder(*parts)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            process.inputStream.bufferedReader().forEachLine {
                output.appendLine(it)
            }
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return ""
            }
            output.toString()
        } catch (_: Exception) {
            ""
        }
    }

    /** `hdc -s <ip>:8710 -t <sn> shell <cmd>`，供键盘文本等低频输入使用 */
    fun shell(device: HosDevice, cmd: String, timeoutSec: Long = 5): String =
        exec("-s ${device.ip}:$HDC_PORT -t ${device.sn} shell $cmd", timeoutSec)

    /** 列出某主机上的设备 sn 列表 */
    fun listTargets(hostIp: String): List<String> {
        val result = exec("-s $hostIp:$HDC_PORT list targets", 10)
        if (result.isBlank() || result.contains("Empty") || result.contains("failed")) {
            return emptyList()
        }
        return result.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /** 本地 + 已配置远程主机上的全部设备 */
    fun listAllDevices(): List<HosDevice> {
        val devices = mutableListOf<HosDevice>()
        listTargets(HosDevice.LOCAL_HOST).forEach { devices += HosDevice(sn = it) }
        AppSettings.remoteHosts.forEach { host ->
            listTargets(host).forEach { devices += HosDevice(ip = host, sn = it) }
        }
        return devices
    }
}

/** 应用配置：持久化到 ~/.harmonytouping/config.properties */
object AppSettings {
    private val dir = File(System.getProperty("user.home"), ".harmonytouping")
    private val file = File(dir, "config.properties")

    /** 远程 hdc 主机 IP 列表（远程真机机位） */
    var remoteHosts: List<String> = emptyList()

    fun load() {
        if (!file.isFile) return
        runCatching {
            val props = java.util.Properties().apply { file.inputStream().use { load(it) } }
            remoteHosts = props.getProperty("remoteHosts", "").split(',', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            props.getProperty("hdcPath")?.takeIf { it.isNotBlank() }?.let { HdcCli.hdcPath = it }
        }
    }

    fun save() {
        runCatching {
            dir.mkdirs()
            val props = java.util.Properties().apply {
                setProperty("remoteHosts", remoteHosts.joinToString(","))
                setProperty("hdcPath", HdcCli.hdcPath)
            }
            file.outputStream().use { props.store(it, "harmonytouping config") }
        }
    }
}
