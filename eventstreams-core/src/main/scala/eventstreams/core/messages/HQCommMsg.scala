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

package eventstreams.core.messages

import akka.actor.ActorRef
import eventstreams.core.agent.core.WireMessage
import play.api.libs.json.JsValue

trait HQCommMsg[T] extends WireMessage {
  val subj: T
}

trait Subj

case class TopicKey(key: String)

case class ComponentKey(key: String) {
  def /(s: String) = ComponentKey(key + "/" + s)
  def toActorId = key.replaceAll("""[\W]""", "_").replaceAll("__", "_")
}

case class LocalSubj(component: ComponentKey, topic: TopicKey) extends Subj {
  override def toString: String = component.key + "#" + topic.key 
}

case class RemoteAddrSubj(address: String, localSubj: LocalSubj) extends Subj {
  override def toString: String = localSubj + "@" + address
}

case class RemoteRoleSubj(role: String, localSubj: LocalSubj) extends Subj {
  override def toString: String = localSubj + "->" + role
}


case class Subscribe(sourceRef: ActorRef, subj: Any) extends HQCommMsg[Any]

case class Unsubscribe(sourceRef: ActorRef, subj: Any) extends HQCommMsg[Any]

case class Command(subj: Any, replyToSubj: Option[Any], data: Option[String] = None) extends HQCommMsg[Any]

case class Update(subj: Any, data: String, canBeCached: Boolean = true) extends HQCommMsg[Any]

case class CommandOk(subj: Any, data: String) extends HQCommMsg[Any]
case class CommandErr(subj: Any, data: String) extends HQCommMsg[Any]


case class Stale(subj: Any) extends HQCommMsg[Any]

case class RegisterComponent(component: ComponentKey, ref: ActorRef)