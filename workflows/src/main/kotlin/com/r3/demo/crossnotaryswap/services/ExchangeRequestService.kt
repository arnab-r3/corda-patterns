package com.r3.demo.crossnotaryswap.services

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.workflows.utilities.heldTokensByToken
import com.r3.corda.lib.tokens.workflows.utilities.tokenBalance
import com.r3.demo.crossnotaryswap.flows.dto.ExchangeAsset
import com.r3.demo.crossnotaryswap.flows.dto.ExchangeRequestDTO
import com.r3.demo.crossnotaryswap.schemas.ExchangeRequest
import com.r3.demo.crossnotaryswap.types.RequestStatus
import com.r3.demo.generic.argFail
import com.r3.demo.generic.flowFail
import net.corda.core.identity.AbstractParty
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken

@CordaService
class ExchangeRequestService(private val appServiceHub: AppServiceHub) : SingletonSerializeAsToken() {

    /**
     * Get Exchange request by requestId
     */
    fun getRequestById(requestId: String): ExchangeRequestDTO =
        ExchangeRequestDTO.fromExchangeRequestEntity(getRequestEntityById(requestId), appServiceHub)


    /**
     * Get Exchange request entity by requestId
     */
    private fun getRequestEntityById(requestId: String): ExchangeRequest =
        appServiceHub.withEntityManager {
            this.find(ExchangeRequest::class.java, requestId)
                ?: argFail("Cannot find exchange request with Id: $requestId")
        }

    /**
     * Set request status of the exchange request
     * @param requestId to be modified
     * @param requestStatus to be set for the [ExchangeRequest]
     */
    fun setRequestStatus(requestId: String, requestStatus: RequestStatus, reason: String? = null) {
        val request = getRequestEntityById(requestId)
        appServiceHub.withEntityManager {
            if (request.requestStatus == null && requestStatus !in listOf(RequestStatus.REQUESTED,
                    RequestStatus.ABORTED)
            ) {
                flowFail("Status update should be REQUESTED/ABORTED for null requestStatus")
            } else if (request.requestStatus == RequestStatus.REQUESTED && request.requestStatus!! in listOf(
                    RequestStatus.APPROVED,
                    RequestStatus.DENIED)
            ) {
                flowFail("Request status has already been set with the response")
            }
            request.requestStatus = requestStatus
            request.reason = reason
            merge(request)
        }
    }

    /**
     * Set the transaction id after completion of the request lifecycle
     * @param requestId to be modified
     * @param txId to be updated in the [ExchangeRequest]
     */
    fun setTxDetails(requestId: String, txId: String, tx: ByteArray) {
        val request = getRequestEntityById(requestId)
        appServiceHub.withEntityManager {
            if (request.requestStatus != null || request.requestStatus == RequestStatus.REQUESTED) {
                flowFail("Cannot set the transaction id for requests with no request status or REQUESTED request status")
            } else if (request.txId != null) {
                flowFail("Transaction id has already been set for request id $requestId")
            }
            request.txId = txId
            request.unsignedTransaction = tx
            merge(request)
        }
    }

    /**
     * Create a new [ExchangeRequest] in the exchange_request table in the db of the local node
     * @param buyer party
     * @param seller party
     * @param buyerAsset details containing the token name and optional amount
     * @param sellerAsset details containing the token name and the optional amount
     */
    fun newExchangeRequest(
        buyer: AbstractParty,
        seller: AbstractParty,
        buyerAsset: ExchangeAsset<out TokenType>,
        sellerAsset: ExchangeAsset<out TokenType>
    ): ExchangeRequestDTO {
        val exchangeRequestDTO = ExchangeRequestDTO(
            buyer = buyer,
            seller = seller,
            buyerAsset = buyerAsset,
            sellerAsset = sellerAsset,
            requestStatus = RequestStatus.REQUESTED
        )
        newExchangeRequestFromDto(exchangeRequestDTO)
        return exchangeRequestDTO
    }


    /**
     * Create a new [ExchangeRequest] from [ExchangeRequestDTO] object in the exchange_request table in the db of
     * the local node
     * @param exchangeRequestDTO
     */
    fun newExchangeRequestFromDto(exchangeRequestDTO: ExchangeRequestDTO) {
        val exchangeRequestEntity = exchangeRequestDTO.toExchangeRequestEntity()
        if (exchangeRequestDTO.requestStatus == null) {
            exchangeRequestEntity.requestStatus = RequestStatus.REQUESTED
        }
        appServiceHub.withEntityManager {
            persist(exchangeRequestEntity)
        }
    }

    /**
     * Checks if the specified asset is owned by our identity
     * @param exchangeAsset to be checked against our vault
     */
    @Suspendable
    fun isExchangeAssetOwned(exchangeAsset: ExchangeAsset<out TokenType>): Boolean {

        val (_, amount) = exchangeAsset.toTokenIdentifierAndAmount()

        return if (exchangeAsset.tokenType.isPointer()) {
            val heldTokensByToken = appServiceHub.vaultService.heldTokensByToken(exchangeAsset.tokenType)
            heldTokensByToken.states.isNotEmpty()
        } else if (exchangeAsset.tokenType.isRegularTokenType() && amount != null) {
            val tokenBalance = appServiceHub.vaultService.tokenBalance(exchangeAsset.tokenType)
            tokenBalance.quantity >= amount
        } else argFail("Cannot determine token type. It needs to be either a regular token type or a token pointer")
    }

    /**
     * Find the [ExchangeRequest] entity with the transaction id and convert it into a [ExchangeRequestDTO]
     * @param transactionId to fetch the [ExchangeRequest] by
     */
    @Suspendable
    fun getExchangeRequestByTxId(transactionId: String): ExchangeRequestDTO {
        val queryResults = appServiceHub.withEntityManager {
            val query = criteriaBuilder.createQuery(ExchangeRequest::class.java).apply {
                val root = from(ExchangeRequest::class.java)
                val txIdEqPredicate = criteriaBuilder.equal(root.get<String>("txId"), transactionId)
                where(criteriaBuilder.and(txIdEqPredicate))
                select(root)
            }
            createQuery(query).resultList
        }
        return ExchangeRequestDTO.fromExchangeRequestEntity(queryResults.first(), appServiceHub)
    }
}