package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.{BinaryData, Transaction, TxIn}
import fr.acinq.eclair.channel.{LocalParams, RemoteParams}
import fr.acinq.eclair.crypto.LightningCrypto.sha256
import fr.acinq.eclair.wire.{UpdateAddHtlc, UpdateFailHtlc, UpdateFulfillHtlc, UpdateMessage}

/**
  * Created by PM on 07/12/2016.
  */

// @formatter:off
sealed trait Direction
case object IN extends Direction
case object OUT extends Direction
// @formatter:on

case class Htlc(direction: Direction, add: UpdateAddHtlc, val previousChannelId: Option[BinaryData])

final case class CommitmentSpec(htlcs: Set[Htlc], feeRate: Long, to_local_msat: Long, to_remote_msat: Long) {
  val totalFunds = to_local_msat + to_remote_msat + htlcs.toSeq.map(_.add.amountMsat).sum
}

object CommitmentSpec {
  def removeHtlc(changes: List[UpdateMessage], id: Long): List[UpdateMessage] = changes.filterNot(_ match {
    case u: UpdateAddHtlc if u.id == id => true
    case _ => false
  })

  def addHtlc(spec: CommitmentSpec, direction: Direction, update: UpdateAddHtlc): CommitmentSpec = {
    val htlc = Htlc(direction, update, previousChannelId = None)
    direction match {
      case OUT => spec.copy(to_local_msat = spec.to_local_msat - htlc.add.amountMsat, htlcs = spec.htlcs + htlc)
      case IN => spec.copy(to_remote_msat = spec.to_remote_msat - htlc.add.amountMsat, htlcs = spec.htlcs + htlc)
    }
  }

  // OUT means we are sending an UpdateFulfillHtlc message which means that we are fulfilling an HTLC that they sent
  def fulfillHtlc(spec: CommitmentSpec, direction: Direction, update: UpdateFulfillHtlc): CommitmentSpec = {
    spec.htlcs.find(htlc => htlc.add.id == update.id && htlc.add.paymentHash == sha256(update.paymentPreimage)) match {
      case Some(htlc) if direction == OUT => spec.copy(to_local_msat = spec.to_local_msat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case Some(htlc) if direction == IN => spec.copy(to_remote_msat = spec.to_remote_msat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None => throw new RuntimeException(s"cannot find htlc id=${update.id}")
    }
  }

  // OUT means we are sending an UpdateFailHtlc message which means that we are failing an HTLC that they sent
  def failHtlc(spec: CommitmentSpec, direction: Direction, update: UpdateFailHtlc): CommitmentSpec = {
    spec.htlcs.find(_.add.id == update.id) match {
      case Some(htlc) if direction == OUT => spec.copy(to_remote_msat = spec.to_remote_msat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case Some(htlc) if direction == IN => spec.copy(to_local_msat = spec.to_local_msat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None => throw new RuntimeException(s"cannot find htlc id=${update.id}")
    }
  }

  def reduce(ourCommitSpec: CommitmentSpec, localChanges: List[UpdateMessage], remoteChanges: List[UpdateMessage]): CommitmentSpec = {
    val spec1 = localChanges.foldLeft(ourCommitSpec) {
      case (spec, u: UpdateAddHtlc) => addHtlc(spec, OUT, u)
      case (spec, _) => spec
    }
    val spec2 = remoteChanges.foldLeft(spec1) {
      case (spec, u: UpdateAddHtlc) => addHtlc(spec, IN, u)
      case (spec, _) => spec
    }
    val spec3 = localChanges.foldLeft(spec2) {
      case (spec, u: UpdateFulfillHtlc) => fulfillHtlc(spec, OUT, u)
      case (spec, u: UpdateFailHtlc) => failHtlc(spec, OUT, u)
      case (spec, _) => spec
    }
    val spec4 = remoteChanges.foldLeft(spec3) {
      case (spec, u: UpdateFulfillHtlc) => fulfillHtlc(spec, IN, u)
      case (spec, u: UpdateFailHtlc) => failHtlc(spec, IN, u)
      case (spec, _) => spec
    }
    spec4
  }

  def makeLocalTxTemplate(localParams: LocalParams, RemoteParams: RemoteParams, inputs: Seq[TxIn], ourRevocationHash: BinaryData, spec: CommitmentSpec): CommitTxTemplate = ???
  //makeCommitTxTemplate(inputs, ourParams.finalPubKey, theirParams.finalPubKey, ourParams.delay, ourRevocationHash, spec)

  def makeLocalTx(localParams: LocalParams, RemoteParams: RemoteParams, inputs: Seq[TxIn], ourRevocationHash: BinaryData, spec: CommitmentSpec): Transaction = ???
  //makeCommitTx(inputs, ourParams.finalPubKey, theirParams.finalPubKey, ourParams.delay, ourRevocationHash, spec)

  def makeRemoteTxTemplate(localParams: LocalParams, RemoteParams: RemoteParams, inputs: Seq[TxIn], theirRevocationHash: BinaryData, spec: CommitmentSpec): CommitTxTemplate = ???
  //makeCommitTxTemplate(inputs, theirParams.finalPubKey, ourParams.finalPubKey, theirParams.delay, theirRevocationHash, spec)

  def makeRemoteTx(localParams: LocalParams, RemoteParams: RemoteParams, inputs: Seq[TxIn], theirRevocationHash: BinaryData, spec: CommitmentSpec): Transaction = ???
  //makeTheirTxTemplate(ourParams, theirParams, inputs, theirRevocationHash, spec).makeTx
}