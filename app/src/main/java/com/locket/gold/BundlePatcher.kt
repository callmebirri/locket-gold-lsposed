package com.locket.gold

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Arrays

internal object BundlePatcher {
    @Volatile
    private var patchedBundlePath: String? = null

    private fun bytes(hex: String): ByteArray {
        val clean = hex.replace("\\s+".toRegex(), "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun context(spec: String): Array<ByteArray> {
        val groups = spec.split("\\|".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val parts = arrayOfNulls<ByteArray>(groups.size)
        for (i in groups.indices) {
            parts[i] = bytes(groups[i])
        }
        @Suppress("UNCHECKED_CAST")
        return parts as Array<ByteArray>
    }

    private fun structuralPatch(data: ByteArray): Boolean {
        try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val version = buf.getInt(8)
            if (version != HERMES_VERSION_SUPPORTED) {
                log("Structural patch: Hermes version $version not supported")
                return false
            }
            val functionCount = buf.getInt(40)
            val stringKindCount = buf.getInt(44)
            val identifierCount = buf.getInt(48)
            val stringCount = buf.getInt(52)
            val overflowCount = buf.getInt(56)
            val stringStorageSize = buf.getInt(60)

            var p = align(data.size, 32, 111)
            val headersStart = p
            p = align(data.size, 4, p + functionCount * 16)
            p = align(data.size, 4, p + stringKindCount * 4)
            p = align(data.size, 4, p + identifierCount * 4)
            val smallStringTablePos = p
            p = align(data.size, 4, p + stringCount * 4)
            val overflowTablePos = p
            p = align(data.size, 4, p + overflowCount * 8)
            val storagePos = p
            if (p + stringStorageSize > data.size) {
                log("Structural patch: string storage out of bounds")
                return false
            }

            val entWant = STR_ENTITLEMENT.toByteArray(charset("UTF-8"))
            var entId = -1
            for (i in 0 until stringCount) {
                val entry = buf.getInt(smallStringTablePos + i * 4).toLong() and 0xFFFFFFFFL
                val isUtf16 = (entry and 1L).toInt()
                if (isUtf16 != 0) continue
                val off = ((entry shr 1) and 0x7FFFFFL).toInt()
                var len = ((entry ushr 24) and 0xFFL).toInt()
                val abs: Int
                if (len == 0xFF) {
                    val ov = buf.getInt(overflowTablePos + off * 8).toLong() and 0xFFFFFFFFL
                    len = buf.getInt(overflowTablePos + off * 8 + 4)
                    abs = storagePos + ov.toInt()
                } else {
                    abs = storagePos + off
                }
                if (len == entWant.size && regionEqualsAscii(data, abs, entWant)) {
                    entId = i
                    break
                }
            }
            if (entId < 0) {
                log("Structural patch: 'subscription_entitlement' string not found")
                return false
            }

            val badgeWant = FN_BADGE.toByteArray(charset("UTF-8"))
            val paymentsWant = FN_PAYMENTS.toByteArray(charset("UTF-8"))
            var badgeOffset = -1
            var badgeSize = 0
            var paymentsOffset = -1
            var paymentsSize = 0
            for (i in 0 until functionCount) {
                val base = headersStart + i * 16
                val w1 = buf.getInt(base + 4)
                val overflowed = ((data[base + 15].toInt() shr 4) and 1) != 0
                if (overflowed) continue
                val nameId = (w1 ushr 15) and 0x1FFFF
                if (nameId >= stringCount) continue

                val entry = buf.getInt(smallStringTablePos + nameId * 4).toLong() and 0xFFFFFFFFL
                val isUtf16 = (entry and 1L).toInt()
                if (isUtf16 != 0) continue
                val off = ((entry shr 1) and 0x7FFFFFL).toInt()
                var len = ((entry ushr 24) and 0xFFL).toInt()
                val abs: Int
                if (len == 0xFF) {
                    val ov = buf.getInt(overflowTablePos + off * 8).toLong() and 0xFFFFFFFFL
                    len = buf.getInt(overflowTablePos + off * 8 + 4)
                    abs = storagePos + ov.toInt()
                } else {
                    abs = storagePos + off
                }

                if (badgeOffset < 0 && len == badgeWant.size && regionEqualsAscii(data, abs, badgeWant)) {
                    badgeOffset = buf.getInt(base) and 0x1FFFFFF
                    badgeSize = w1 and 0x7FFF
                } else if (paymentsOffset < 0 && len == paymentsWant.size && regionEqualsAscii(data, abs, paymentsWant)) {
                    paymentsOffset = buf.getInt(base) and 0x1FFFFFF
                    paymentsSize = w1 and 0x7FFF
                }
                if (badgeOffset >= 0 && paymentsOffset >= 0) break
            }
            if (badgeOffset < 0 || paymentsOffset < 0) {
                log("Structural patch: target functions not found (badge=" + (badgeOffset >= 0)
                        + " payments=" + (paymentsOffset >= 0) + ")")
                return false
            }

            if (badgeSize < 4 || (data[badgeOffset + badgeSize - 2].toInt() and 0xFF) != 0x5C) {
                log("Structural patch: badge function shape unexpected")
                return false
            }
            val retReg = data[badgeOffset + badgeSize - 1].toInt() and 0xFF
            data[badgeOffset] = 0x78.toByte()
            data[badgeOffset + 1] = retReg.toByte()
            data[badgeOffset + 2] = 0x5C.toByte()
            data[badgeOffset + 3] = retReg.toByte()
            log("Structural patch doesUserHaveGoldBadge: applied at file offset 0x"
                    + Integer.toHexString(badgeOffset))

            val idLe = byteArrayOf((entId and 0xFF).toByte(), ((entId shr 8) and 0xFF).toByte())
            val body = Arrays.copyOfRange(data, paymentsOffset, paymentsOffset + paymentsSize)
            for (i in 0..body.size - 12) {
                if (body[i + 4] == idLe[0] && body[i + 5] == idLe[1]
                        && body[i + 6] == 0x0B.toByte() && body[i + 7] == body[i + 8]
                        && body[i + 9] == 0x0B.toByte() && body[i + 11] == body[i + 7]) {
                    val d1 = body[i + 7].toInt() and 0xFF
                    val d2 = body[i + 10].toInt() and 0xFF
                    val at = paymentsOffset + i + 6
                    data[at] = 0x78.toByte()
                    data[at + 1] = d2.toByte()
                    data[at + 2] = 0x79.toByte()
                    data[at + 3] = d1.toByte()
                    data[at + 4] = 0x78.toByte()
                    data[at + 5] = d2.toByte()
                    log("Structural patch hasGoldSubscription: applied at file offset 0x"
                            + Integer.toHexString(at))
                    return true
                }
            }
            log("Structural patch: Not/Not sequence not found in PaymentsProvider")
            return false
        } catch (t: Throwable) {
            log("Structural patch error: $t")
            return false
        }
    }

    private fun align(total: Int, alignment: Int, position: Int): Int {
        val aligned = (position + alignment - 1) and (alignment - 1).inv()
        if (aligned > total) return Int.MAX_VALUE - 1
        return aligned
    }

    private fun regionEqualsAscii(data: ByteArray, pos: Int, want: ByteArray): Boolean {
        if (pos < 0 || pos + want.size > data.size) return false
        for (i in want.indices) {
            if (data[pos + i] != want[i]) return false
        }
        return true
    }

    @Synchronized
    fun preparePatchedBundle(context: Context): String? {
        val currentPatchedPath = patchedBundlePath
        if (currentPatchedPath != null && File(currentPatchedPath).exists()) {
            return currentPatchedPath
        }
        try {
            val bos = ByteArrayOutputStream()
            val `is` = context.assets.open(BUNDLE_ASSET)
            val temp = ByteArray(8192)
            var read: Int
            while (`is`.read(temp).also { read = it } != -1) {
                bos.write(temp, 0, read)
            }
            `is`.close()
            val buffer = bos.toByteArray()

            val md5 = MessageDigest.getInstance("MD5")
            val md5Hex = toHex(md5.digest(buffer))

            val outFile = File(context.filesDir, BUNDLE_CACHE_PREFIX + CACHE_VERSION + "_" + md5Hex + ".bin")
            if (outFile.exists() && outFile.length() == buffer.size.toLong()) {
                patchedBundlePath = outFile.absolutePath
                log("Reusing cached patched bundle " + outFile.name)
                cleanupOldBundles(context, outFile.name)
                return patchedBundlePath
            }

            var appliedFingerprint: String? = null
            if (structuralPatch(buffer)) {
                appliedFingerprint = "structural"
            } else {
                for (fp in FINGERPRINTS) {
                    val ctx1 = context(fp[1])
                    val new1 = bytes(fp[2])
                    val ctx2 = context(fp[3])
                    val new2 = bytes(fp[4])

                    if (!contextMatchesOnce(buffer, ctx1, "hasGoldSubscription/" + fp[0])) continue
                    if (!contextMatchesOnce(buffer, ctx2, "goldBadge/" + fp[0])) continue

                    val p1 = patchUnique(buffer, ctx1, new1, "hasGoldSubscription")
                    val p2 = patchUnique(buffer, ctx2, new2, "doesUserHaveGoldBadge")
                    if (p1 && p2) {
                        appliedFingerprint = fp[0]
                        break
                    }
                    log("Fingerprint " + fp[0] + " matched dry-run but failed to apply — trying next")
                }
            }

            if (appliedFingerprint == null) {
                log("No bundle fingerprint matched this Locket build (md5 " + md5Hex
                        + ") — keeping ORIGINAL bundle; runtime hooks will try to unlock. "
                        + "Please report the md5 to add a fingerprint.")
                return null
            }

            val sha1 = MessageDigest.getInstance("SHA-1")
            sha1.update(buffer, 0, buffer.size - 20)
            val digest = sha1.digest()
            System.arraycopy(digest, 0, buffer, buffer.size - 20, 20)

            val fos = FileOutputStream(outFile)
            fos.write(buffer)
            fos.close()

            patchedBundlePath = outFile.absolutePath
            log("Generated patched Hermes bundle " + outFile.name
                    + " (method " + appliedFingerprint + ", size " + buffer.size + ")")
            cleanupOldBundles(context, outFile.name)
            return patchedBundlePath
        } catch (t: Throwable) {
            log("Error preparing patched bundle: $t")
            return null
        }
    }

    private fun contextMatchesOnce(src: ByteArray, context: Array<ByteArray>, name: String): Boolean {
        val full = concat(context, 0, context.size)
        val first = indexOf(src, full, 0)
        if (first < 0) {
            log("Fingerprint check $name: context NOT found")
            return false
        }
        if (indexOf(src, full, first + 1) >= 0) {
            log("Fingerprint check $name: context occurs MULTIPLE times")
            return false
        }
        return true
    }

    private fun patchUnique(src: ByteArray, context: Array<ByteArray>, replacement: ByteArray, name: String): Boolean {
        val prefix = concat(context, 0, context.size - 2)
        val full = concat(context, 0, context.size)
        val innerLen = context[context.size - 2].size
        if (innerLen != replacement.size) {
            log("Patch " + name + ": replacement size mismatch (" + replacement.size
                    + " vs " + innerLen + ")")
            return false
        }

        val first = indexOf(src, full, 0)
        if (first < 0) {
            log("Patch " + name + ": context NOT found in bundle")
            return false
        }

        val innerStart = first + prefix.size
        System.arraycopy(replacement, 0, src, innerStart, replacement.size)
        log("Patch " + name + ": applied at file offset 0x" + Integer.toHexString(innerStart)
                + " (inner " + replacement.size + " bytes)")
        return true
    }

    private fun concat(parts: Array<ByteArray>, from: Int, to: Int): ByteArray {
        var len = 0
        for (i in from until to) len += parts[i].size
        val out = ByteArray(len)
        var pos = 0
        for (i in from until to) {
            System.arraycopy(parts[i], 0, out, pos, parts[i].size)
            pos += parts[i].size
        }
        return out
    }

    private fun indexOf(src: ByteArray, pat: ByteArray, from: Int): Int {
        if (pat.isEmpty()) return from
        val start = Math.max(0, from)
        val end = src.size - pat.size
        outer@ for (i in start..end) {
            for (j in pat.indices) {
                if (src[i + j] != pat[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    private fun cleanupOldBundles(context: Context, keepName: String) {
        val dir = context.filesDir
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.name.startsWith(BUNDLE_CACHE_PREFIX) && f.name != keepName) {
                f.delete()
            }
        }
    }
}
