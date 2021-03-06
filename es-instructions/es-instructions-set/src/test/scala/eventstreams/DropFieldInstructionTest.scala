package eventstreams

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

import eventstreams.EventFrameConverter.optionsConverter
import eventstreams.instructions.{DropFieldInstruction, DropFieldInstructionConstants, SimpleInstructionBuilder}
import eventstreams.support.TestHelpers
import play.api.libs.json._

class DropFieldInstructionTest extends TestHelpers {


  trait WithBasicConfig extends WithSimpleInstructionBuilder with DropFieldInstructionConstants {
    override def builder: SimpleInstructionBuilder = new DropFieldInstruction()

    override def config: JsValue = Json.obj(
      CfgFClass -> "dropfield",
      CfgFFieldToDrop -> "abc")
  }

  s"DropFieldInstruction with simple config" should s"not build without required fields" in new WithSimpleInstructionBuilder {
    override def builder: SimpleInstructionBuilder = new DropFieldInstruction()

    override def config: JsValue = Json.obj("class" -> "dropfield")

    shouldNotBuild()
  }

  it should "be built with valid config" in new WithBasicConfig {
    shouldBuild()
  }

  it should "raise event when built" in new WithBasicConfig {
    expectEvent(EventFrame("abc" -> "bla"))(Built)
  }

  it should "raise event when tag dropped" in new WithBasicConfig {
    expectEvent(EventFrame("abc" -> "bla", "tags" -> Seq("abc")))(FieldDropped, 'Field -> "abc")
  }

  trait WithAdvancedConfig extends WithSimpleInstructionBuilder with DropFieldInstructionConstants {
    override def builder: SimpleInstructionBuilder = new DropFieldInstruction()

    override def config: JsValue = Json.obj(
      CfgFClass -> "dropfield",
      CfgFFieldToDrop -> "${source}_abc")
  }

  "DropFieldInstruction with advanced config" should "be built with valid config" in new WithAdvancedConfig {
    shouldBuild()
  }

  it should "drop existing field" in new WithAdvancedConfig {
    expectOne(EventFrame("fname_abc" -> 1, "source" -> "fname")) { result =>
      result +> 'fname_abc should be(None)
    }
  }

  it should "not touch any other field" in new WithAdvancedConfig {
    expectOne(EventFrame("fname_abc" -> 1, "source" -> "fname")) { result =>
      result ~> 'source should be(Some("fname"))
    }
  }

  it should "drop existing branch" in new WithAdvancedConfig {
    expectOne(EventFrame("fname_abc" -> EventFrame("x" -> "some"), "source" -> "fname")) { result =>
      result #> 'fname_abc ~> 'x should be(None)
    }
  }

  it should "not touch any existing branch" in new WithAdvancedConfig {
    expectOne(EventFrame("fname_abc" -> EventFrame("x" -> "some"), "fname" -> EventFrame("x" -> "some"), "source" -> "fname")) { result =>
      result #> 'fname_abc ~> 'x should be(None)
      result #> 'fname ~> 'x should be(Some("some"))
    }
  }

  it should "drop existing array" in new WithAdvancedConfig {
    expectOne(EventFrame("fname_abc" -> Seq("x", "some"), "source" -> "fname")) { result =>
      result #> 'fname_abc should be(Some(EventDataValueNil()))
    }
  }


  trait WithWildcardConfig extends WithSimpleInstructionBuilder with DropFieldInstructionConstants {
    override def builder: SimpleInstructionBuilder = new DropFieldInstruction()

    override def config: JsValue = Json.obj(
      CfgFClass -> "dropfield",
      CfgFFieldToDrop -> "?abc")
  }

  "DropFieldInstruction with wildcard config" should "be built with valid config" in new WithWildcardConfig {
    shouldBuild()
  }

  val testInput = EventFrame("abc" -> 1, "branch" -> EventFrame("abc" -> "f2", "b2" -> EventFrame("abc" -> EventFrame("x" -> "some"), "xyz" -> 2)), "source" -> "fname")
  
  it should "drop existing field" in new WithWildcardConfig {
    expectOne(testInput) { result =>
      result +> 'abc should be(None)
      result #> 'branch ~> 'abc should be(None)
      result #> 'branch #> 'b2 #> 'abc ~> 'x should be(None)
      expectOneOrMoreEvents(FieldDropped, 'Field -> "abc")
    }
  }

  it should "not touch any other field" in new WithWildcardConfig {
    expectOne(testInput) { result =>
      result #> 'branch #> 'b2 +> 'xyz should be(Some(2))
    }
  }



}
