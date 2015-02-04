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

package eventstreams.agent.datasource

import akka.actor.{Actor, ActorRef, Props}
import akka.stream.actor.ActorSubscriberMessage.OnNext
import akka.stream.actor.{MaxInFlightRequestStrategy, RequestStrategy}
import com.typesafe.config.Config
import core.events.EventOps.symbolToEventOps
import core.events.WithEventPublisher
import eventstreams.core.EventFrame
import eventstreams.core.actors._
import eventstreams.core.agent.core.{GateState, ProducedMessage}
import net.ceedubs.ficus.Ficus._
import play.api.libs.json.JsValue

import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scalaz.Scalaz._
import scalaz._

trait DatasourceSinkEvents 
  extends DatasourceActorEvents
  with BaseActorEvents with StateChangeEvents with ReconnectingActorEvents with GateMonitorEvents with AtLeastOnceDeliveryActorEvents  {
  override def componentId: String = super.componentId + ".Sink"
  
  val MessageAcknowledged = 'MessageAcknowledged.trace
}

object SubscriberBoundaryInitiatingActor extends DatasourceSinkEvents {
  def props(endpoint: String, maxInFlight: Int)(implicit sysconfig: Config) =
    Props(new SubscriberBoundaryInitiatingActor(endpoint, maxInFlight))
}

class SubscriberBoundaryInitiatingActor(endpoint: String, maxInFlight: Int)(implicit sysconfig: Config)
  extends PipelineWithStatesActor
  with StoppableSubscriberActor
  with ReconnectingActor
  with AtLeastOnceDeliveryActor[EventFrame]
  with ActorWithGateStateMonitoring 
  with DatasourceSinkEvents with WithEventPublisher {

  override val gateStateCheckInterval: FiniteDuration = sysconfig.as[Option[FiniteDuration]]("eventstreams.agent.gate-check-interval") | 10.seconds

  override def reconnectAttemptInterval: FiniteDuration = sysconfig.as[Option[FiniteDuration]]("eventstreams.agent.gate-reconnect-attempt-interval") | super.reconnectAttemptInterval
  override def remoteAssociationTimeout: FiniteDuration = sysconfig.as[Option[FiniteDuration]]("eventstreams.agent.gate-handshake-timeout") | super.remoteAssociationTimeout

  override def commonBehavior: Receive = handleOnNext orElse super.commonBehavior

  override def connectionEndpoint: String = endpoint

  val correlationToCursor = mutable.Map[Long, JsValue]()

  override def onConnectedToEndpoint(): Unit = {
    super.onConnectedToEndpoint()
    startGateStateMonitoring()
  }

  override def onDisconnectedFromEndpoint(): Unit = {
    super.onDisconnectedFromEndpoint()
    stopGateStateMonitoring()
    if (isComponentActive) initiateReconnect()
  }

  override def becomeActive(): Unit = {
    initiateReconnect()
  }

  override def becomePassive(): Unit = {
    stopGateStateMonitoring()
    disconnect()
  }

  override def onGateStateChanged(state: GateState): Unit = {
    deliverIfPossible()
    super.onGateStateChanged(state)
  }


  override def canDeliverDownstreamRightNow = isComponentActive && connected && isGateOpen

  override def getSetOfActiveEndpoints: Set[ActorRef] = remoteActorRef.map(Set(_)).getOrElse(Set())

  override def fullyAcknowledged(correlationId: Long, msg: EventFrame): Unit = {
    MessageAcknowledged >> ('CorrelationId -> correlationId)
    context.parent ! Acknowledged(correlationId, correlationToCursor.get(correlationId))
    correlationToCursor -= correlationId
  }


  override protected def requestStrategy: RequestStrategy = new MaxInFlightRequestStrategy(maxInFlight) {
    override def inFlightInternally: Int = inFlightCount
  }

  private def handleOnNext: Actor.Receive = {
    case OnNext(ProducedMessage(value, cursor)) =>
      val correlationId = deliverMessage(value)
      cursor.foreach(correlationToCursor += correlationId -> _)
    case OnNext(x) =>
      Error >> ('Message -> "unsupported payload", 'Payload -> x)
  }
}