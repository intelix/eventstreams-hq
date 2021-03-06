/*
 * Copyright 2014-15 Intelix Pty Ltd
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

package eventstreams.signals

import _root_.core.sysevents.WithSyseventPublisher
import _root_.core.sysevents.ref.ComponentWithBaseSysevents
import akka.actor.Props
import akka.stream.actor.{MaxInFlightRequestStrategy, RequestStrategy}
import eventstreams._
import Tools._
import eventstreams.alerts.{AlertLevel, Alert}
import eventstreams.instructions.{DateInstructionConstants, Types}
import Types._
import eventstreams.core._
import eventstreams.core.actors.{ActorWithTicks, StoppableSubscribingPublisherActor, WithOccurrenceAccounting}
import org.joda.time.DateTime
import play.api.libs.json.{JsString, JsValue, Json}

import scala.collection.mutable
import scalaz.Scalaz._
import scalaz._

trait SignalSensorInstructionSysevents extends ComponentWithBaseSysevents {
  override def componentId: String = "Instruction.SignalSensor"
}

class SignalSensorInstruction extends BuilderFromConfig[InstructionType] {
  val configId = "sensor"

  override def build(props: JsValue, maybeState: Option[JsValue], id: Option[String] = None): \/[Fail, InstructionType] =
    for (
      signalClass <- props ~> 'signalClass orFail s"Invalid sensor instruction. Missing 'signalClass' value. Contents: ${Json.stringify(props)}"
    ) yield SignalSensorInstructionActor.props(signalClass, props)

}


private object SignalSensorInstructionActor {
  def props(signalClass: String, config: JsValue) = Props(new SignalSensorInstructionActor(signalClass, config))
}

private class SignalSensorInstructionActor(signalClass: String, props: JsValue)
  extends StoppableSubscribingPublisherActor
  with ActorWithTicks
  with WithOccurrenceAccounting
  with NowProvider
  with SignalSensorInstructionSysevents
  with WithSyseventPublisher {

  implicit val ec = context.dispatcher
  val occurrenceCondition = (props ~> 'occurrenceCondition | "None").toLowerCase match {
    case "less than" => OccurrenceConditionLessThan()
    case "more than" => OccurrenceConditionMoreThan()
    case "exactly" => OccurrenceConditionExactly()
    case _ => OccurrenceConditionNone()
  }
  val occurrenceCount = props +> 'occurrenceCount | -1
  val occurrenceWatchPeriodSec = (props +> 'occurrenceWatchPeriodSec | 1) match {
    case x if x < 1 => 1
    case x => x
  }
  val simpleCondition = SimpleCondition.conditionOrAlwaysTrue(props ~> 'simpleCondition).get
  val title = props ~> 'title
  val body = props ~> 'body
  val icon = props ~> 'icon
  val expirySec = props +> 'expirySec
  val correlationIdTemplate = props ~> 'correlationIdTemplate
  val conflationKeyTemplate = props ~> 'conflationKeyTemplate
  val timestampSource = props ~> 'timestampSource
  val signalSubclass = props ~> 'signalSubclass
  val throttlingWindow = props +> 'throttlingWindow
  val throttlingAllowance = props +> 'throttlingAllowance
  val level = AlertLevel.fromString(props ~> 'level | "Very low")
  val (transactionDemarcation, transactionStatus) = (props ~> 'transactionDemarcation | "None").toLowerCase match {
    case "start" => (Some("start"), Some("open"))
    case "success" => (Some("success"), Some("closed"))
    case "failure" => (Some("failure"), Some("closed"))
    case "none" => (None, None)
  }
  val buckets = mutable.Map[BucketKey, Int]()
  private val maxInFlight = props +> 'buffer | 1000
  var sequenceCounter: Long = 0


  override def occurrenceAccountingPeriodSec: Int = throttlingWindow match {
    case Some(x) if x > 0 => x
    case _ => 1
  }


  def bucketIdByTs(ts: Long): Long = ts / 1000

  def currentBucket: Long = bucketIdByTs(now)

  def cleanup() = {
    val validBucketId = currentBucket - occurrenceWatchPeriodSec + 1
    buckets.collect { case (k, v) if k.bucketId < validBucketId => (k, v)} foreach {
      case (k, v) =>
        buckets.remove(k)
    }
  }

  def countSignals(cId: Option[String]) = buckets.foldLeft(0) { (count, mapEntry) =>
    mapEntry match {
      case (bucketId, bucketCount) if bucketId.correlationId == cId => count + bucketCount
      case _ => count
    }
  }


  override def onBecameActive(): Unit = {
    resetCounters()
    super.onBecameActive()
  }

  def accountSignal(s: Alert) = {
    logger.debug(s"Candidate signal $s")
    cleanup()
    val bucketId = bucketIdByTs(s.ts)
    val validBucket = currentBucket - occurrenceWatchPeriodSec + 1
    if (bucketId >= validBucket) {
      val key = BucketKey(bucketId, s.correlationId)
      buckets += key -> (buckets.getOrElse(key, 0) + 1)
    }
  }

  def eventToSignal(e: EventFrame): Alert = {

    sequenceCounter = sequenceCounter + 1

    val eventId = e.eventIdOrNA
    val signalId = eventId + ":" + sequenceCounter
    val ts = timestampSource.flatMap { tsSource => Tools.locateRawFieldValue(e, tsSource, now).asNumber.map(_.longValue())} | now

    Alert(signalId, sequenceCounter, ts,
      eventId, level, signalClass, signalSubclass,
      conflationKeyTemplate.map(Tools.macroReplacement(e, _)),
      correlationIdTemplate.map(Tools.macroReplacement(e, _)),
      transactionDemarcation, transactionStatus,
      title.map(Tools.macroReplacement(e, _)),
      body.map(Tools.macroReplacement(e, _)),
      icon.map(Tools.macroReplacement(e, _)),
      expirySec.map(_ * 1000 + now))
  }

  def signalToEvent(s: Alert): EventFrame = EventFrame(
    "eventId" -> s.signalId,
    "eventSeq" -> s.sequenceId,
    DateInstructionConstants.default_targetFmtField -> DateTime.now().toString(DateInstructionConstants.default),
    DateInstructionConstants.default_targetTsField -> DateTime.now().getMillis,
    "transaction" -> (transactionDemarcation.map { demarc =>
      Map(
        "demarcation" -> demarc,
        "status" -> JsString(transactionStatus.getOrElse("unknown"))
      )
    } | Map()),
    "signal" -> Map(
      "sourceEventId" -> s.eventId,
      "conflationKey" -> s.conflationKey,
      "correlationId" -> s.correlationId,
      "signalClass" -> s.signalClass,
      "signalSubclass" -> s.signalSubclass,
      "title" -> s.title,
      "body" -> s.body,
      "icon" -> s.icon,
      "expiryTs" -> s.expiryTs,
      "level" -> s.level.code,
      "time" -> s.ts,
      "time_fmt" -> new DateTime(s.ts).toString(DateInstructionConstants.default)
    )
  )


  def throttlingAllowed() = throttlingAllowance match {
    case Some(x) if x > 0 =>
      x > accountedOccurrencesCount
    case _ => true
  }


  override def execute(frame: EventFrame): Option[Seq[EventFrame]] = {
    if (simpleCondition.metFor(frame).isRight) {
      val signal = eventToSignal(frame)
      accountSignal(signal)
      if (throttlingAllowed() && occurrenceCondition.isMetFor(occurrenceCount, countSignals(signal.correlationId))) {
        mark(now)
        Some(List(frame, signalToEvent(signal)))
      } else {
        Some(List(frame))
      }
    } else Some(List(frame))
  }


  override def internalProcessTick(): Unit = {
    super.internalProcessTick()
    if (occurrenceCount == 0
      && isActive
      && isComponentActive
      && millisTimeSinceStateChange > occurrenceWatchPeriodSec * 1000
      && countSignals(correlationIdTemplate.map { s => Tools.macroReplacement(EventFrame(), s)}) == 0) {
      if (throttlingAllowed()) {
        mark(now)
        pushSingleEventToStream(signalToEvent(eventToSignal(EventFrame("eventId" -> UUIDTools.generateShortUUID, "ts" -> now))))
      }
    }
  }

  override protected def requestStrategy: RequestStrategy = new MaxInFlightRequestStrategy(maxInFlight) {
    override def inFlightInternally: Int =
      pendingToDownstreamCount
  }


}

sealed trait OccurrenceCondition {
  def isMetFor(count: Int, currentCount: Long): Boolean
}

case class OccurrenceConditionNone() extends OccurrenceCondition {
  override def isMetFor(count: Int, currentCount: Long): Boolean = true
}

case class OccurrenceConditionMoreThan() extends OccurrenceCondition {
  override def isMetFor(count: Int, currentCount: Long): Boolean = count > -1 && currentCount > count
}

case class OccurrenceConditionLessThan() extends OccurrenceCondition {
  override def isMetFor(count: Int, currentCount: Long): Boolean = count > -1 && currentCount < count
}

case class OccurrenceConditionExactly() extends OccurrenceCondition {
  override def isMetFor(count: Int, currentCount: Long): Boolean = count > -1 && currentCount == count
}

private case class BucketKey(bucketId: Long, correlationId: Option[String])

