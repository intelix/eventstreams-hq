/*
 * Copyright 2014 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hq.flows.core

import agent.controller.flow.Tools
import akka.actor.Props
import akka.stream.actor.{MaxInFlightRequestStrategy, RequestStrategy}
import common.ToolExt.configHelper
import common.actors.{ActorWithTicks, SubscribingPublisherActor}
import common.{Fail, JsonFrame, NowProvider, Utils}
import hq.flows.core.Builder._
import hq.gates.WithOccurrenceAccounting
import hq.signals.{Signal, SignalLevel}
import org.joda.time.DateTime
import play.api.libs.json._

import scala.collection.mutable
import scalaz.Scalaz._
import scalaz._

class TransactionInstruction extends BuilderFromConfig[InstructionType] {
  val configId = "sensor"

  override def build(props: JsValue, maybeState: Option[JsValue], id: Option[String] = None): \/[Fail, InstructionType] =
    for (
      correlationIdTemplate <- props ~> 'correlationIdTemplate \/> Fail(s"Invalid transaction instruction. Missing 'correlationIdTemplate' value. Contents: ${Json.stringify(props)}")
    ) yield TransactionInstructionActor.props(correlationIdTemplate, props)

}

private object TransactionInstructionActor {
  def props(correlationIdTemplate: String, config: JsValue) = Props(new TransactionInstructionActor(correlationIdTemplate, config))
}

private class TransactionInstructionActor(correlationIdTemplate: String, props: JsValue)
  extends SubscribingPublisherActor
  with ActorWithTicks
  with NowProvider {

  implicit val ec = context.dispatcher

  val name = props ~> 'name | "default"
  val uniqueTransactionIdTemplate = props ~> 'uniqueTransactionIdTemplate
  val txStartCondition = SimpleCondition.optionalCondition(props ~> 'txStartCondition)
  val txEndSuccessCondition = SimpleCondition.optionalCondition(props ~> 'txEndSuccessCondition)
  val txEndFailureCondition = SimpleCondition.optionalCondition(props ~> 'txEndFailureCondition)
  val txAliveCondition = SimpleCondition.optionalCondition(props ~> 'txAliveCondition)


  val maxEvents = props +> 'maxEvents
  val minEvents = props +> 'minEvents
  val maxDurationMs = props +> 'maxDurationMs

  val maxOpenTransactions = props +> 'maxOpenTransactions | 100000
  val timestampSource = props ~> 'timestampSource | DateDefaults.default_targetTsField

  /*
  demarcationLogic match {
    case None => logger.error(s"Invalid combination of transaction demarcation conditions. Transaction instruction disabled.")
    case Some(l) => logger.debug(s"Demarcation logic: $l")
  }
  */
  val targetTxField = props ~> 'targetTxField | "transactionId"
  val targetTxValueTemplate = props ~> 'targetTxValueTemplate
  private val maxInFlight = props +> 'buffer | 1000
  //  var currentOpenTransactions = 0
  //  var sequenceCounter: Long = 0

  /*
  val txByCorrelationId = mutable.Map[String, List[Transaction]]()
*/

  /*
  var demarcationLogic = uniqueTransactionIdTemplate match {
    case Some(tpl) => Some(DemarcationWithUniqueId(tpl, minEvents))
    case None => txEndSuccessCondition match {
      case Some(cond) if txStartCondition.isDefined => Some(DemarcationWithStartAndStop(txStartCondition.get, cond, txEndFailureCondition))
      case Some(cond) if txAliveCondition.isDefined => Some(DemarcationWithAlivesAndStop(txAliveCondition.get, cond, txEndFailureCondition))
      case None => txStartCondition match {
        case Some(cond) if txAliveCondition.isDefined => Some(DemarcationWithStartAndAlives(cond, txAliveCondition.get, minEvents, maxEvents))
        case None if txAliveCondition.isDefined => Some(DemarcationWithAlivesOnly(txAliveCondition.get, maxEvents, maxDurationMs))
        case None => None
      }
    }
  }
*/

  override protected def requestStrategy: RequestStrategy = new MaxInFlightRequestStrategy(maxInFlight) {
    override def inFlightInternally: Int =
      pendingToDownstreamCount
  }

  override def becomeActive(): Unit = {
    super.becomeActive()
  }

  /*
  def toEvent(t: Transaction): JsonFrame = {
    // TODO
    return JsonFrame(Json.obj(), Map())
  }
*/

/*
  def accountTransaction(frame: JsonFrame): Option[Seq[JsonFrame]] =
    for (
      logic <- demarcationLogic;

      ts <- Tools.locateFieldValue(frame, timestampSource).asOpt[Long];

      correlationId <- Tools.templateToStringValue(frame, correlationIdTemplate);

      seq <- if (logic.isApplicableFromFrame(frame)) {
        var list = txByCorrelationId.getOrElse(correlationId, List())

        type AffectedTx = Option[Transaction]
        type ListOfClosedTxs = Option[List[Transaction]]
        type ListOfOpenTxs = List[Transaction]
        type FoldingResult = (AffectedTx, ListOfClosedTxs, ListOfOpenTxs)

        def closeNextTx(nextTx: Transaction, currentList: ListOfClosedTxs) = currentList.map(nextTx.close() +: _)

        list.foldRight[FoldingResult]((None, None, List())) { (nextTx, foldingResult) =>
          foldingResult match {
            case (currentAffectedTx, currentListOfClosedTxs, currentListOfOpenTxs) =>
              currentAffectedTx match {
                case Some(tx) if tx.isCompleted => (currentAffectedTx, closeNextTx(nextTx, currentListOfClosedTxs), currentListOfOpenTxs)
                case Some(tx) => (currentAffectedTx, None, nextTx +: currentListOfOpenTxs)
                case None if nextTx.lastEventTs > ts => (None, None, nextTx +: currentListOfOpenTxs)
                case None => logic.applyTo(nextTx, ts, frame) match {
                  case Some(newTx) if newTx.isCompleted => (Some(newTx), closeNextTx(newTx, Some(List())), currentListOfOpenTxs)
                  case Some(newTx) => (Some(newTx), None, newTx +: currentListOfOpenTxs)
                  case None => (None, None, nextTx +: currentListOfOpenTxs)
                }
              }
          }
        } match {
          case result@(Some(tx), closedTxs, openTxs) =>
            txByCorrelationId += correlationId -> openTxs
            logger.debug(s"Affected existing tx: $tx, total tx count for correlation $correlationId: ${openTxs.size + 1}, closed count: ${closedTxs.map(_.size)}, total in-flight correlations: ${txByCorrelationId.size}")
            closedTxs
          case (None, _, openTxs) =>
            logic.applyTo(logic.newTx(ts, frame), ts, frame).foreach { newTx =>
                logger.debug(s"New tx: $newTx, total tx count for correlation $correlationId: ${openTxs.size + 1}, total in-flight correlations: ${txByCorrelationId.size}")
                txByCorrelationId += correlationId -> (newTx +: openTxs)
            }
            None
        }
      } else None

    ) yield seq.map(toEvent)


  override def execute(frame: JsonFrame): Option[Seq[JsonFrame]] =
    accountTransaction(frame) match {
      case Some(tx) => Some(List(frame) ++ tx)
      case _ => Some(List(frame))
    }




}


case class Transaction(completeCheckFunc: Transaction => Boolean, tranId: String = Utils.generateShortUUID) {

  def hasDefinedStart: Boolean

  def hasDefinedFinish: Boolean

  def firstEventTs: Long

  def lastEventTs: Long

  def eventsCount: Int

  def isCompleted: Boolean

  def close(): Transaction

  def add(ts: Long, frame: JsonFrame): Transaction

  def markSuccess(): Transaction

  def markFailure(): Transaction

  def markHasStart(): Transaction

  def markHasFinish(): Transaction

  def markCompleted(completed: Boolean): Transaction
}


sealed trait DemarcationLogic {
  def isApplicableFromFrame(frame: JsonFrame): Boolean

  def applyTo(tx: Transaction, ts: Long, frame: JsonFrame): Option[Transaction]

  def newTx(ts: Long, frame: JsonFrame): Transaction

}

case class DemarcationWithUniqueId(uniqueIdTemplate: String, minEvents: Option[Int]) extends DemarcationLogic {

  val minEventsReq = minEvents | 2

  override def isApplicableFromFrame(frame: JsonFrame): Boolean = Tools.templateToStringValue(frame, uniqueIdTemplate).isDefined

  def applyTo(tx: Transaction, ts: Long, frame: JsonFrame): Option[Transaction] = Tools.templateToStringValue(frame, uniqueIdTemplate) match {
    case Some(v) if tx.tranId == v => Some(tx.add(ts, frame))
    case _ => None
  }

  override def newTx(ts: Long, frame: JsonFrame): Transaction =
    Transaction(_.eventsCount >= minEventsReq, Tools.templateToStringValue(frame, uniqueIdTemplate)|"undefined")
}

case class DemarcationWithStartAndStop(txStart: Condition, txStopSuccess: Condition, txStopFailure: Option[Condition]) extends DemarcationLogic {
  override def isApplicableFromFrame(frame: JsonFrame): Boolean =
    txStart.metFor(frame).isRight || txStopSuccess.metFor(frame).isRight || (txStopFailure.map(_.metFor(frame).isRight) | false)


  def applyTo(tx: Transaction, ts: Long, frame: JsonFrame): Option[Transaction] = tx match {
    case x if !x.hasDefinedFinish && txStopSuccess.metFor(frame).isRight =>
      Some(tx.add(ts, frame).markHasFinish().markSuccess())
    case x if !x.hasDefinedFinish && (txStopFailure.map(_.metFor(frame).isRight) | false) =>
      Some(tx.add(ts, frame).markHasFinish().markFailure())
    case x if !x.hasDefinedStart && txStart.metFor(frame).isRight =>
      Some(tx.add(ts, frame).markHasStart())
    case _ => None
  }

  override def newTx(ts: Long, frame: JsonFrame): Transaction = Transaction(t => t.hasDefinedFinish && t.hasDefinedStart)


}

case class DemarcationWithStartAndAlives(txStart: Condition, txAlive: Condition, minEvents: Option[Int], maxEvents: Option[Int]) extends DemarcationLogic {

  val minEventsReq = minEvents | 2


  override def isApplicableFromFrame(frame: JsonFrame): Boolean =
    txStart.metFor(frame).isRight || txAlive.metFor(frame).isRight

  def applyTo(tx: Transaction, ts: Long, frame: JsonFrame): Option[Transaction] = tx match {
    case x if !x.hasDefinedStart && txStart.metFor(frame).isRight =>
      Some(tx.add(ts, frame).markHasStart())
    case x if txAlive.metFor(frame).isRight =>
      Some(tx.add(ts, frame))
    case _ => None
  }

  override def newTx(ts: Long, frame: JsonFrame): Transaction =
    Transaction(t => t.hasDefinedStart).add(ts, frame)

}

case class DemarcationWithAlivesOnly(txAlive: Condition, maxEvents: Option[Int], maxDuration: Option[Int]) extends DemarcationLogic {
  override def isApplicableFromFrame(frame: JsonFrame): Boolean =
    txAlive.metFor(frame).isRight

  def applyTo(tx: Transaction, ts: Long, frame: JsonFrame): Option[Transaction] = tx match {
    case x if txAlive.metFor(frame).isRight =>
      Some(tx.add(ts, frame))
    case _ => None
  }

}

case class DemarcationWithAlivesAndStop(txAlive: Condition, txStopSuccess: Condition, txStopFailure: Option[Condition]) extends DemarcationLogic {
  override def isApplicableFromFrame(frame: JsonFrame): Boolean =
    txAlive.metFor(frame).isRight || txStopSuccess.metFor(frame).isRight || (txStopFailure.map(_.metFor(frame).isRight) | false)

  def applyTo(tx: Transaction, ts: Long, frame: JsonFrame): Option[Transaction] = tx match {
    case x if !x.hasDefinedFinish && txStopSuccess.metFor(frame).isRight =>
      Some(tx.add(ts, frame).markHasFinish().markSuccess())
    case x if !x.hasDefinedFinish && (txStopFailure.map(_.metFor(frame).isRight) | false) =>
      Some(tx.add(ts, frame).markHasFinish().markFailure())
    case x if txAlive.metFor(frame).isRight =>
      Some(tx.add(ts, frame))
    case _ => None
  }
*/
  override def execute(value: JsonFrame): Option[Seq[JsonFrame]] = None  // TODO
}