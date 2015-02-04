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

package eventstreams.plugins.essentials

import core.events.EventOps.symbolToEventOps
import core.events.WithEventPublisher
import core.events.ref.ComponentWithBaseEvents
import eventstreams.core.Tools.{configHelper, _}
import eventstreams.core.Types.SimpleInstructionType
import eventstreams.core._
import eventstreams.core.instructions.{InstructionConstants, SimpleInstructionBuilder}
import play.api.libs.json.{JsString, JsValue, Json}

import scalaz.Scalaz._
import scalaz._


trait AddTagInstructionEvents
  extends ComponentWithBaseEvents {

  val Built = 'Built.trace
  val TagAdded = 'TagAdded.trace

  override def componentId: String = "Instruction.AddTag"
}

trait AddTagInstructionConstants extends InstructionConstants with AddTagInstructionEvents  {
  val CfgFTagToAdd = "tagToAdd"
}

class AddTagInstruction extends SimpleInstructionBuilder with AddTagInstructionConstants with WithEventPublisher {
  val configId = "addtag"

  override def simpleInstruction(props: JsValue, id: Option[String] = None): \/[Fail, SimpleInstructionType] =
    for (
      tagName <- props ~> CfgFTagToAdd \/> Fail(s"Invalid $configId instruction. Missing '$CfgFTagToAdd' value. Contents: ${Json.stringify(props)}")
    ) yield {

      val uuid = Utils.generateShortUUID

      Built >> ('Tag ->  tagName, 'InstructionInstanceId -> uuid)

      frame: EventFrame => {

        val fieldName = "tags"
        val fieldType = "as"

        val keyPath = macroReplacement(frame, fieldName)

        val replacement = macroReplacement(frame, tagName)

        val value: EventFrame = setValue(fieldType, replacement, keyPath, frame)

        val eventId = value.eventIdOrNA
        
        TagAdded >>('Tag -> tagName, 'EventId -> eventId, 'InstructionInstanceId -> uuid)

        List(value)

      }
    }


}