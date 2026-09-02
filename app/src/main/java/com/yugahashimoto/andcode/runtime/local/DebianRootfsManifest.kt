package com.yugahashimoto.andcode.runtime.local

data class DebianRootfsAsset(
    val blobUrl: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class DebianPackageAsset(
    val name: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** Pinned Debian Bookworm slim OCI layers used when Alpine gcompat cannot load agy. */
object DebianRootfsManifest {
    private const val TOKEN_URL = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/debian:pull"
    private const val BLOB_BASE = "https://registry-1.docker.io/v2/library/debian/blobs/"

    val tokenUrl: String = TOKEN_URL

    // bookworm-slim omits /usr/bin/script, which is required to give agy a real PTY.
    private val bsdutilsAssets =
        mapOf(
            "arm64-v8a" to
                DebianPackageAsset(
                    name = "bsdutils",
                    url = "https://deb.debian.org/debian/pool/main/u/util-linux/bsdutils_2.38.1-5+deb12u3_arm64.deb",
                    sha256 = "d3aef8318c0d72d06141968a764a544a4f41a7cc10e4a87fd2ef932d6557b5ef",
                    sizeBytes = 94_216L,
                ),
            "x86_64" to
                DebianPackageAsset(
                    name = "bsdutils",
                    url = "https://deb.debian.org/debian/pool/main/u/util-linux/bsdutils_2.38.1-5+deb12u3_amd64.deb",
                    sha256 = "6cae172b006a4603e710e046c3acba8d98d36748894cbe3eeda31d415fec331e",
                    sizeBytes = 94_412L,
                ),
        )

    private val assets =
        mapOf(
            "arm64-v8a" to
                DebianRootfsAsset(
                    blobUrl = BLOB_BASE + "sha256:53bb9e501f1803aca595be8d902a62cea6bf4d996ce6f7dfe16c1c97be343e6c",
                    sha256 = "53bb9e501f1803aca595be8d902a62cea6bf4d996ce6f7dfe16c1c97be343e6c",
                    sizeBytes = 28_117_255,
                ),
            "x86_64" to
                DebianRootfsAsset(
                    blobUrl = BLOB_BASE + "sha256:597c6c618d36213af657a6a8444a5d87801f9a219682b206ad21ccb8f3e57bbd",
                    sha256 = "597c6c618d36213af657a6a8444a5d87801f9a219682b206ad21ccb8f3e57bbd",
                    sizeBytes = 28_232_643,
                ),
        )

    fun assetFor(abi: String): DebianRootfsAsset = assets[abi] ?: error("Debian Bookworm does not support ABI $abi")

    fun bsdutilsFor(abi: String): DebianPackageAsset = bsdutilsAssets[abi] ?: error("Unsupported ABI $abi")
}
