package com.pcbiounlock.cloud

data class PairedPc(
    val localDeviceId: String,
    val name: String,
    val userName: String,
    val encryptionKey: String,
    val passwordKey: String,
    val cloudDeviceId: String,
    val cloudSigningPublicKey: String,
    val cloudExchangePublicKey: String,
    val udpPort: Int = 43300
)

data class CloudSession(val accountToken: String, val accountId: String, val phoneDeviceId: String, val phoneDeviceToken: String)
