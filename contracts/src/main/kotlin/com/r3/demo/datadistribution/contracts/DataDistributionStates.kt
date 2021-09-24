package com.r3.demo.datadistribution.contracts

import net.corda.bn.states.GroupState
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearPointer
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty


@BelongsToContract(GroupDataAssociationContract::class)
data class GroupDataAssociationState(
    override val linearId: UniqueIdentifier = UniqueIdentifier(),
    val value: Any?,
    val associatedGroupStates: Set<LinearPointer<GroupState>>?,
    override val participants: List<AbstractParty>
): LinearState
