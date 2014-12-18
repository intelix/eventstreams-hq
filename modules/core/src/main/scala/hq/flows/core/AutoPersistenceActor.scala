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

import akka.actor.{ActorSelection, ActorRef, Props}
import akka.stream.actor.{MaxInFlightRequestStrategy, RequestStrategy}
import common.ToolExt.configHelper
import common.actors._
import common.{Fail, JsonFrame}
import hq.flows.core.Builder._
import hq.gates.{ScheduleStorage, RetentionManagerActor}
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}
import scalaz.Scalaz._
import scalaz.\/



object AutoPersistenceActor {
  def props(id: String) = Props(new AutoPersistenceActor(id: String))
}


class AutoPersistenceActor(id: String)
  extends SubscribingPublisherActor
  with AtLeastOnceDeliveryActor[ScheduleStorage]
  with ActorWithResolver {

  val maxInFlight = 1000

  var retentionManagerActor : Set[ActorRef] = Set()

  override def preStart(): Unit = {
    super.preStart()
    self ! RetryResolution(RetentionManagerActor.path)
  }


  override def onPathResolved(path: ActorSelection, ref: ActorRef): Unit = {
    retentionManagerActor = Set(ref)
    logger.debug(s"Retention manager resolved at $ref")
  }

  override def becomeActive(): Unit = {
    logger.info(s"AutoPersistenceActor becoming active")
  }

  override def becomePassive(): Unit = {
    logger.info(s"AutoPersistenceActor becoming passive")
  }

  override def canDeliverDownstreamRightNow = isActive && isPipelineActive && !retentionManagerActor.isEmpty

  override def getSetOfActiveEndpoints: Set[ActorRef] = retentionManagerActor

  override def fullyAcknowledged(correlationId: Long, msg: ScheduleStorage): Unit = {
    logger.info(s"Stored $correlationId id ${msg.id}")
  }

  override def execute(value: JsonFrame): Option[Seq[JsonFrame]] = {
    // TODO log failed condition

    for (
      index <- value.event ~> 'index;
      table <- value.event ~> 'table;
      id <- value.event ~> 'eventId
    ) {
      logger.debug(s"Scheduled storage for id $id")
      deliverMessage(ScheduleStorage(self, index, table, id, value.event))
    }

    Some(List(value))
  }

  override protected def requestStrategy: RequestStrategy = new MaxInFlightRequestStrategy(maxInFlight) {
    override def inFlightInternally: Int = inFlightCount + pendingToDownstreamCount
  }

}