package com.smart

import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.Peer
import com.smart.SmartConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

object WgConfigMultiResolver {

    private const val TAG = "WgConfigMultiResolver"
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val CLASS_IN = 1

    // 共享 OkHttpClient，启用 HTTP/2 连接池以复用 TCP 连接
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 解析配置文件中所有的 Endpoint
     * @param config 原始配置 (保持结构不变)
     * @param dohUrl 标准 DoH 地址 (如 https://dns.google/dns-query)
     */
    suspend fun resolveAllEndpoints(config: Config, dohUrl: String): Config = withContext(Dispatchers.IO) {
        val builder = Config.Builder().setInterface(config.`interface`)

        config.peers.forEach { peer ->
            val updatedPeer = resolvePeerEndpoint(peer, dohUrl)
            builder.addPeer(updatedPeer)
        }

        return@withContext builder.build()
    }

    private suspend fun resolvePeerEndpoint(peer: Peer, dohUrl: String): Peer {
        val endpointOptional = peer.endpoint
        if (!endpointOptional.isPresent) return peer

        val endpoint = endpointOptional.get()
        val host = endpoint.host
        val port = endpoint.port

        if (isIpAddress(host)) {
            SmartConfigRepository.logDebug("Skipping IP endpoint: $host", tag = TAG)
            return peer
        }

        val resolvedIp = resolveHostDualStack(host, dohUrl)
        if (resolvedIp == null) {
            SmartConfigRepository.logDebug("Failed to resolve: $host, keeping original.", tag = TAG, level = "ERROR")
            return peer
        }

        SmartConfigRepository.logDebug("Replaced: $host -> $resolvedIp", tag = TAG, level = "INFO")
        val formattedHost = if (resolvedIp.contains(":")) "[$resolvedIp]" else resolvedIp
        val newEndpoint = InetEndpoint.parse("$formattedHost:$port")
        return copyPeerWithEndpoint(peer, newEndpoint)
    }

    /**
     * 双栈并发解析：同时查 AAAA 和 A，优先返回 IPv6
     */
    private suspend fun resolveHostDualStack(host: String, dohUrl: String): String? = coroutineScope {
        SmartConfigRepository.logDebug("Resolving $host via RFC8484...", tag = TAG)
        
        // 启动两个并发任务
        val ipv6Job = async { queryDohRfc8484(host, TYPE_AAAA, dohUrl) }
        val ipv4Job = async { queryDohRfc8484(host, TYPE_A, dohUrl) }

        val ipv6 = ipv6Job.await()
        val ipv4 = ipv4Job.await()

        // 优先返回 IPv6
        return@coroutineScope ipv6 ?: ipv4
    }

    /**
     * 发送 RFC 8484 DoH 请求 (POST binary)
     */
    private fun queryDohRfc8484(host: String, type: Int, dohUrl: String): String? {
        try {
            val queryBytes = buildDnsQuery(host, type)
            val requestBody = queryBytes.toRequestBody("application/dns-message".toMediaType())
            val request = Request.Builder()
                .url(dohUrl)
                .header("Accept", "application/dns-message")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBytes = response.body?.bytes()

            if (response.isSuccessful && responseBytes != null && responseBytes.isNotEmpty()) {
                return parseDnsResponse(responseBytes, type)
            } else {
                SmartConfigRepository.logDebug(
                    "DoH RFC8484 HTTP Error: ${response.code}",
                    tag = TAG,
                    level = "WARN"
                )
            }
        } catch (e: Exception) {
            SmartConfigRepository.logDebug("DoH RFC8484 Error ($host): ${e.message}", tag = TAG, level = "WARN")
        }
        return null
    }

    private fun buildDnsQuery(host: String, type: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        val id = java.util.Random().nextInt(0xFFFF)
        dos.writeShort(id)
        dos.writeShort(0x0100) // standard query, recursion desired
        dos.writeShort(1) // QDCOUNT
        dos.writeShort(0) // ANCOUNT
        dos.writeShort(0) // NSCOUNT
        dos.writeShort(0) // ARCOUNT

        host.split(".").forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0) // root label terminator
        dos.writeShort(type)
        dos.writeShort(CLASS_IN)

        return baos.toByteArray()
    }

    private fun parseDnsResponse(data: ByteArray, expectedType: Int): String? {
        val dis = DataInputStream(ByteArrayInputStream(data))

        dis.readShort() // ID
        val flags = dis.readShort().toInt() and 0xFFFF
        val qdCount = dis.readShort().toInt() and 0xFFFF
        val anCount = dis.readShort().toInt() and 0xFFFF
        dis.readShort() // NSCOUNT
        dis.readShort() // ARCOUNT

        // RCODE non-zero -> error
        if ((flags and 0x000F) != 0) return null

        repeat(qdCount) {
          skipName(dis, data)
          dis.skipBytes(4) // QTYPE + QCLASS
        }

        repeat(anCount) {
          skipName(dis, data)
          val type = dis.readShort().toInt() and 0xFFFF
          val clazz = dis.readShort().toInt() and 0xFFFF
          dis.readInt() // TTL
          val rdLength = dis.readShort().toInt() and 0xFFFF
          if (type == expectedType && clazz == CLASS_IN) {
            val rdata = ByteArray(rdLength)
            dis.readFully(rdata)
            return runCatching { java.net.InetAddress.getByAddress(rdata).hostAddress }.getOrNull()
          } else {
            dis.skipBytes(rdLength)
          }
        }
        return null
    }

    private fun skipName(dis: DataInputStream, rawData: ByteArray) {
        while (true) {
            val length = dis.readUnsignedByte()
            if (length == 0) break
            if ((length and 0xC0) == 0xC0) {
                dis.readUnsignedByte() // pointer second byte
                break
            } else {
                dis.skipBytes(length)
            }
        }
    }

    private fun copyPeerWithEndpoint(peer: Peer, endpoint: InetEndpoint): Peer {
        val builder = Peer.Builder()
            .setPublicKey(peer.publicKey)
        peer.allowedIps.forEach { builder.addAllowedIp(it) }

        peer.preSharedKey.ifPresent { builder.setPreSharedKey(it) }
        peer.persistentKeepalive.ifPresent { builder.setPersistentKeepalive(it) }
        builder.setEndpoint(endpoint)
        return builder.build()
    }

    private fun isIpAddress(input: String): Boolean {
        val clean = input.replace("[", "").replace("]", "")
        return clean.contains(":") || clean.matches(Regex("^[0-9.]+$"))
    }
}
