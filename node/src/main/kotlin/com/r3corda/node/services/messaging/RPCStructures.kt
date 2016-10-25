@file:JvmName("RPCStructures")

package com.r3corda.node.services.messaging

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Registration
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.serializers.JavaSerializer
import com.google.common.net.HostAndPort
import com.r3corda.contracts.asset.Cash
import com.r3corda.core.ErrorOr
import com.r3corda.core.contracts.*
import com.r3corda.core.crypto.*
import com.r3corda.core.node.NodeInfo
import com.r3corda.core.node.PhysicalLocation
import com.r3corda.core.node.ServiceEntry
import com.r3corda.core.node.services.NetworkMapCache
import com.r3corda.core.node.services.ServiceInfo
import com.r3corda.core.node.services.StateMachineTransactionMapping
import com.r3corda.core.node.services.Vault
import com.r3corda.core.protocols.StateMachineRunId
import com.r3corda.core.serialization.*
import com.r3corda.core.transactions.SignedTransaction
import com.r3corda.core.transactions.WireTransaction
import com.r3corda.node.services.User
import de.javakaffee.kryoserializers.ArraysAsListSerializer
import de.javakaffee.kryoserializers.guava.*
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import org.objenesis.strategy.StdInstantiatorStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rx.Notification
import rx.Observable
import java.time.Instant
import java.util.*

/** Global RPC logger */
val rpcLog: Logger by lazy { LoggerFactory.getLogger("com.r3corda.rpc") }

/** Used in the RPC wire protocol to wrap an observation with the handle of the observable it's intended for. */
data class MarshalledObservation(val forHandle: Int, val what: Notification<*>)

/**
 * If an RPC is tagged with this annotation it may return one or more observables anywhere in its response graph.
 * Calling such a method comes with consequences: it's slower, and consumes server side resources as observations
 * will buffer up on the server until they're consumed by the client.
 */
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class RPCReturnsObservables

/** Records the protocol version in which this RPC was added. */
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class RPCSinceVersion(val version: Int)

/** The contents of an RPC request message, separated from the MQ layer. */
data class ClientRPCRequestMessage(
        val args: SerializedBytes<Array<Any>>,
        val replyToAddress: String,
        val observationsToAddress: String?,
        val methodName: String,
        val user: User
) {
    companion object {
        const val REPLY_TO = "reply-to"
        const val OBSERVATIONS_TO = "observations-to"
        const val METHOD_NAME = "method-name"
    }
}

/**
 * Base interface that all RPC servers must implement. Note: in Corda there's only one RPC interface. This base
 * interface is here in case we split the RPC system out into a separate library one day.
 */
interface RPCOps {
    /** Returns the RPC protocol version. Exists since version 0 so guaranteed to be present. */
    val protocolVersion: Int
}

/**
 * This is available to RPC implementations to query the validated [User] that is calling it. Each user has a set of
 * permissions they're entitled to which can be used to control access.
 */
@JvmField
val CURRENT_RPC_USER: ThreadLocal<User> = ThreadLocal()

/** Helper method which checks that the current RPC user is entitled for the given permission. Throws a [PermissionException] otherwise. */
fun requirePermission(permission: String) {
    if (permission !in CURRENT_RPC_USER.get().permissions) {
        throw PermissionException("User not permissioned for $permission")
    }
}

/**
 * Thrown to indicate a fatal error in the RPC system itself, as opposed to an error generated by the invoked
 * method.
 */
open class RPCException(msg: String, cause: Throwable?) : RuntimeException(msg, cause) {
    constructor(msg: String) : this(msg, null)
    class DeadlineExceeded(rpcName: String) : RPCException("Deadline exceeded on call to $rpcName")
}

class PermissionException(msg: String) : RuntimeException(msg)

// The Kryo used for the RPC wire protocol. Every type in the wire protocol is listed here explicitly.
// This is annoying to write out, but will make it easier to formalise the wire protocol when the time comes,
// because we can see everything we're using in one place.
private class RPCKryo(observableSerializer: Serializer<Observable<Any>>? = null) : Kryo() {
    init {
        isRegistrationRequired = true
        // Allow construction of objects using a JVM backdoor that skips invoking the constructors, if there is no
        // no-arg constructor available.
        instantiatorStrategy = Kryo.DefaultInstantiatorStrategy(StdInstantiatorStrategy())

        register(Arrays.asList("").javaClass, ArraysAsListSerializer())
        register(Instant::class.java, ReferencesAwareJavaSerializer)
        register(SignedTransaction::class.java, ImmutableClassSerializer(SignedTransaction::class))
        register(WireTransaction::class.java, WireTransactionSerializer)
        register(SerializedBytes::class.java, SerializedBytesSerializer)
        register(Party::class.java)

        ImmutableListSerializer.registerSerializers(this)
        ImmutableSetSerializer.registerSerializers(this)
        ImmutableSortedSetSerializer.registerSerializers(this)
        ImmutableMapSerializer.registerSerializers(this)
        ImmutableMultimapSerializer.registerSerializers(this)

        noReferencesWithin<WireTransaction>()

        register(ErrorOr::class.java)
        register(MarshalledObservation::class.java, ImmutableClassSerializer(MarshalledObservation::class))
        register(Notification::class.java)
        register(Notification.Kind::class.java)

        register(ArrayList::class.java)
        register(listOf<Any>().javaClass) // EmptyList
        register(IllegalStateException::class.java)
        register(Pair::class.java)
        register(StateMachineUpdate.Added::class.java)
        register(StateMachineUpdate.Removed::class.java)
        register(StateMachineInfo::class.java)
        register(DigitalSignature.WithKey::class.java)
        register(DigitalSignature.LegallyIdentifiable::class.java)
        register(ByteArray::class.java)
        register(EdDSAPublicKey::class.java, Ed25519PublicKeySerializer)
        register(EdDSAPrivateKey::class.java, Ed25519PrivateKeySerializer)
        register(Vault::class.java)
        register(Vault.Update::class.java)
        register(StateMachineRunId::class.java)
        register(StateMachineTransactionMapping::class.java)
        register(UUID::class.java)
        register(LinkedHashSet::class.java)
        register(StateAndRef::class.java)
        register(setOf<Unit>().javaClass) // EmptySet
        register(StateRef::class.java)
        register(SecureHash.SHA256::class.java)
        register(TransactionState::class.java)
        register(Cash.State::class.java)
        register(Amount::class.java)
        register(Issued::class.java)
        register(PartyAndReference::class.java)
        register(OpaqueBytes::class.java)
        register(Currency::class.java)
        register(Cash::class.java)
        register(Cash.Clauses.ConserveAmount::class.java)
        register(listOf(Unit).javaClass) // SingletonList
        register(setOf(Unit).javaClass) // SingletonSet
        register(TransactionBuildResult.ProtocolStarted::class.java)
        register(TransactionBuildResult.Failed::class.java)
        register(ServiceEntry::class.java)
        register(NodeInfo::class.java)
        register(PhysicalLocation::class.java)
        register(NetworkMapCache.MapChange::class.java)
        register(NetworkMapCache.MapChangeType::class.java)
        register(ArtemisMessagingComponent.NodeAddress::class.java,
                read = { kryo, input ->
                    ArtemisMessagingComponent.NodeAddress(
                            parsePublicKeyBase58(kryo.readObject(input, String::class.java)),
                            kryo.readObject(input, HostAndPort::class.java))
                },
                write = { kryo, output, nodeAddress ->
                    kryo.writeObject(output, nodeAddress.identity.toBase58String())
                    kryo.writeObject(output, nodeAddress.hostAndPort)
                }
        )
        register(HostAndPort::class.java)
        register(ServiceInfo::class.java, read = { kryo, input -> ServiceInfo.parse(input.readString()) }, write = Kryo::writeObject)
        // Exceptions. We don't bother sending the stack traces as the client will fill in its own anyway.
        register(IllegalArgumentException::class.java)
        // Kryo couldn't serialize Collections.unmodifiableCollection in Throwable correctly, causing null pointer exception when try to access the deserialize object.
        register(NoSuchElementException::class.java, JavaSerializer())
        register(RPCException::class.java)
        register(Array<StackTraceElement>::class.java, read = { kryo, input -> emptyArray() }, write = { kryo, output, o -> })
        register(Collections.unmodifiableList(emptyList<String>()).javaClass)
    }

    // Helper method, attempt to reduce boiler plate code
    private fun <T> register(type: Class<T>, read: (Kryo, Input) -> T, write: (Kryo, Output, T) -> Unit) {
        register(type, object : Serializer<T>() {
            override fun read(kryo: Kryo, input: Input, type: Class<T>): T = read(kryo, input)
            override fun write(kryo: Kryo, output: Output, o: T) = write(kryo, output, o)
        })
    }

    val observableRegistration: Registration? = if (observableSerializer != null) register(Observable::class.java, observableSerializer) else null

    override fun getRegistration(type: Class<*>): Registration {
        if (Observable::class.java.isAssignableFrom(type))
            return observableRegistration ?: throw IllegalStateException("This RPC was not annotated with @RPCReturnsObservables")
        return super.getRegistration(type)
    }
}

fun createRPCKryo(observableSerializer: Serializer<Observable<Any>>? = null): Kryo = RPCKryo(observableSerializer)
