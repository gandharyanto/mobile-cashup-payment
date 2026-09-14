package com.cashup.signing

import java.security.KeyPair

/**
 * Supplies the EC key pair used to sign outgoing requests. The private key
 * is expected to live in hardware-backed storage (Android Keystore, TEE
 * floor, StrongBox opportunistic — see the Provisioning plan) and never
 * leaves it; this interface only exposes the [KeyPair] handle needed to
 * sign and verify, not raw key material.
 *
 * Returns null before the device has been provisioned.
 */
fun interface SigningKeyProvider {
    fun currentKeyPair(): KeyPair?
}
