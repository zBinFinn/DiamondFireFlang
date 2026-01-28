package com.zbinfinn.nbt

import java.io.*
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

fun ByteArray.fromBase64(): ByteArray {
    return Base64.getDecoder().decode(this)
}

fun ByteArray.toBase64(): ByteArray {
    return Base64.getEncoder().encode(this)
}

fun ByteArray.fromGzip(): ByteArray {
    val gis = GZIPInputStream(ByteArrayInputStream(this))
    val bf = BufferedReader(InputStreamReader(gis, StandardCharsets.UTF_8))
    val outStr = StringBuilder()
    var line: String?
    while ((bf.readLine().also { line = it }) != null) {
        outStr.append(line)
    }

    return outStr.toString().toByteArray()
}

fun ByteArray.toGzip(): ByteArray {
    val obj = ByteArrayOutputStream()
    val gzip = GZIPOutputStream(obj)
    gzip.write(this)
    gzip.close()

    return obj.toByteArray()
}