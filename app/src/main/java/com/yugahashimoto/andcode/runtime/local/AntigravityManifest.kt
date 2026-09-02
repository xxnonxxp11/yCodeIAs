package com.yugahashimoto.andcode.runtime.local

import java.io.File

data class AntigravityAsset(
    val name: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** Pinned official Google release metadata. The Termux fork is intentionally not an asset source. */
object AntigravityManifest {
    const val VERSION = "1.1.7"
    const val BINARY_NAME = "agy"
    const val MIN_FREE_BYTES = 300L * 1024L * 1024L
    private const val BASE = "https://github.com/google-antigravity/antigravity-cli/releases/download/1.1.7/"

    val arm64 =
        AntigravityAsset(
            name = "agy_cli_linux_arm64.tar.gz",
            url = BASE + "agy_cli_linux_arm64.tar.gz",
            sha256 = "0d6d488851745e80e69b8935d063e742945811b47111994b1a6dbd27df3010d5",
            sizeBytes = 49_049_369L,
        )
    val x64 =
        AntigravityAsset(
            name = "agy_cli_linux_x64.tar.gz",
            url = BASE + "agy_cli_linux_x64.tar.gz",
            sha256 = "946cd06258d0ede72d0311550c914315798821f6a397f53ac760919826a19af4",
            sizeBytes = 52_427_182L,
        )

    fun assetFor(abi: String): AntigravityAsset =
        when (abi) {
            "arm64-v8a", "aarch64" -> arm64
            "x86_64", "amd64" -> x64
            else -> error("Antigravity official Linux release does not support ABI $abi")
        }

    fun verifyArchive(
        file: File,
        abi: String,
    ) {
        RuntimeArchive.verifySha256(file, assetFor(abi).sha256)
    }
}
