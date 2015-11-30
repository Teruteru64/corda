package contracts

import core.*
import java.security.PublicKey
import java.time.Instant

/**
 * This is an ultra-trivial implementation of commercial paper, which is essentially a simpler version of a corporate
 * bond. It can be seen as a company-specific currency. A company issues CP with a particular face value, say $100,
 * but sells it for less, say $90. The paper can be redeemed for cash at a given date in the future. Thus this example
 * would have a 10% interest rate with a single repayment. Commercial paper is often rolled over (redeemed and the
 * money used to immediately rebuy).
 *
 * This contract is not intended to realistically model CP. It is here only to act as a next step up above cash in
 * the prototyping phase. It is thus very incomplete.
 *
 * Open issues:
 *  - In this model, you cannot merge or split CP. Can you do this normally? We could model CP as a specialised form
 *    of cash, or reuse some of the cash code? Waiting on response from Ayoub and Rajar about whether CP can always
 *    be split/merged or only in secondary markets. Even if current systems can't do this, would it be a desirable
 *    feature to have anyway?
 */

val CP_PROGRAM_ID = SecureHash.sha256("replace-me-later-with-bytecode-hash")

// TODO: Generalise the notion of an owned instrument into a superclass/supercontract. Consider composition vs inheritance.
class CommercialPaper : Contract {
    // TODO: should reference the content of the legal agreement, not its URI
    override val legalContractReference: SecureHash = SecureHash.sha256("https://en.wikipedia.org/wiki/Commercial_paper")

    data class State(
        val issuance: InstitutionReference,
        val owner: PublicKey,
        val faceValue: Amount,
        val maturityDate: Instant
    ) : ContractState {
        override val programRef = CP_PROGRAM_ID

        fun withoutOwner() = copy(owner = NullPublicKey)
    }

    interface Commands : Command {
        object Move : Commands
        object Redeem : Commands
        // We don't need a nonce in the issue command, because the issuance.reference field should already be unique per CP.
        // However, nothing in the platform enforces that uniqueness: it's up to the issuer.
        object Issue : Commands
    }

    override fun verify(tx: TransactionForVerification) {
        // Group by everything except owner: any modification to the CP at all is considered changing it fundamentally.
        val groups = tx.groupStates<State>() { it.withoutOwner() }

        // There are two possible things that can be done with this CP. The first is trading it. The second is redeeming
        // it for cash on or after the maturity date.
        val command = tx.commands.requireSingleCommand<CommercialPaper.Commands>()

        for (group in groups) {
            when (command.value) {
                is Commands.Move -> {
                    val input = group.inputs.single()
                    requireThat {
                        "the transaction is signed by the owner of the CP" by (command.signers.contains(input.owner))
                        "the state is propagated" by (group.outputs.size == 1)
                    }
                }

                is Commands.Redeem -> {
                    val input = group.inputs.single()
                    val received = tx.outStates.sumCashBy(input.owner)
                    requireThat {
                        "the paper must have matured" by (input.maturityDate < tx.time)
                        "the received amount equals the face value" by (received == input.faceValue)
                        "the paper must be destroyed" by group.outputs.isEmpty()
                        "the transaction is signed by the owner of the CP" by (command.signers.contains(input.owner))
                    }
                }

                is Commands.Issue -> {
                    val output = group.outputs.single()
                    requireThat {
                        // Don't allow people to issue commercial paper under other entities identities.
                        "the issuance is signed by the claimed issuer of the paper" by
                                (command.signers.contains(output.issuance.institution.owningKey))
                        "the face value is not zero" by (output.faceValue.pennies > 0)
                        "the maturity date is not in the past" by (output.maturityDate > tx.time)
                        // Don't allow an existing CP state to be replaced by this issuance.
                        "there is no input state" by group.inputs.isEmpty()
                    }
                }

                // TODO: Think about how to evolve contracts over time with new commands.
                else -> throw IllegalArgumentException("Unrecognised command")
            }
        }
    }

    /**
     * Returns a transaction that issues commercial paper, owned by the issuing institution's key. Does not update
     * an existing transaction because you aren't able to issue multiple pieces of CP in a single transaction
     * at the moment: this restriction is not fundamental and may be lifted later.
     */
    fun craftIssue(issuance: InstitutionReference, faceValue: Amount, maturityDate: Instant): PartialTransaction {
        val state = State(issuance, issuance.institution.owningKey, faceValue, maturityDate)
        return PartialTransaction(state, WireCommand(Commands.Issue, issuance.institution.owningKey))
    }

    /**
     * Updates the given partial transaction with an input/output/command to reassign ownership of the paper.
     */
    fun craftMove(tx: PartialTransaction, paper: StateAndRef<State>, newOwner: PublicKey) {
        tx.addInputState(paper.ref)
        tx.addOutputState(paper.state.copy(owner = newOwner))
        tx.addArg(WireCommand(Commands.Move, paper.state.owner))
    }

    /**
     * Intended to be called by the issuer of some commercial paper, when an owner has notified us that they wish
     * to redeem the paper. We must therefore send enough money to the key that owns the paper to satisfy the face
     * value, and then ensure the paper is removed from the ledger.
     *
     * @throws InsufficientBalanceException if the wallet doesn't contain enough money to pay the redeemer
     */
    @Throws(InsufficientBalanceException::class)
    fun craftRedeem(tx: PartialTransaction, paper: StateAndRef<State>, wallet: List<StateAndRef<Cash.State>>) {
        // Add the cash movement using the states in our wallet.
        Cash().craftSpend(tx, paper.state.faceValue, paper.state.owner, wallet)
        tx.addInputState(paper.ref)
        tx.addArg(WireCommand(CommercialPaper.Commands.Redeem, paper.state.owner))
    }
}
