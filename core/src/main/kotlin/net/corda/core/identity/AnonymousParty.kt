package net.corda.core.identity

import net.corda.core.contracts.PartyAndReference
import net.corda.core.crypto.toBase58String
import net.corda.core.serialization.OpaqueBytes
import org.bouncycastle.asn1.x500.X500Name
import java.security.PublicKey

/**
 * The [AnonymousParty] class contains enough information to uniquely identify a [Party] while excluding private
 * information such as name. It is intended to represent a party on the distributed ledger.
 */
class AnonymousParty(owningKey: PublicKey) : AbstractParty(owningKey) {
    override fun equals(other: Any?): Boolean {
        return if (other is AnonymousParty) {
            this.owningKey == other.owningKey
        } else {
            false
        }
    }

    override fun hashCode(): Int = owningKey.hashCode()

    // Use the key as the bulk of the toString(), but include a human readable identifier as well, so that [Party]
    // can put in the key and actual name
    override fun toString() = "${owningKey.toBase58String()} <Anonymous>"

    override val nameOrNull: X500Name? = null

    override fun ref(bytes: OpaqueBytes): PartyAndReference = PartyAndReference(this, bytes)
    override fun toAnonymous() = this
}