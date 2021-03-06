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

package eventstreams.instructions

import _root_.core.sysevents.SyseventOps.symbolToSyseventOps
import _root_.core.sysevents.WithSyseventPublisher
import _root_.core.sysevents.ref.ComponentWithBaseSysevents
import eventstreams.Tools.{configHelper, _}
import eventstreams._
import eventstreams.instructions.Types.SimpleInstructionType
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.json.{JsValue, Json}

import scala.util.Try
import scalaz.Scalaz._
import scalaz._

/**
 *
 * Symbol  Meaning                      Presentation  Examples
 * ------  -------                      ------------  -------
 * G       era                          text          AD
 * C       century of era (>=0)         number        20
 * Y       year of era (>=0)            year          1996
 *
 * x       weekyear                     year          1996
 * w       week of weekyear             number        27
 * e       day of week                  number        2
 * E       day of week                  text          Tuesday; Tue
 *
 * y       year                         year          1996
 * D       day of year                  number        189
 * M       month of year                month         July; Jul; 07
 * d       day of month                 number        10
 *
 * a       halfday of day               text          PM
 * K       hour of halfday (0~11)       number        0
 * h       clockhour of halfday (1~12)  number        12
 *
 * H       hour of day (0~23)           number        0
 * k       clockhour of day (1~24)      number        24
 * m       minute of hour               number        30
 * s       second of minute             number        55
 * S       fraction of second           number        978
 *
 * z       time zone                    text          Pacific Standard Time; PST
 * Z       time zone offset/id          zone          -0800; -08:00; America/Los_Angeles
 *
 * '       escape for text              delimiter
 * double'      single quote                 literal       '
 *
 */

trait DateInstructionSysevents extends ComponentWithBaseSysevents {

  val Built = 'Built.trace
  val DateParsed = 'DateParsed.trace
  val UnableToParseDate = 'UnableToParse.info

  override def componentId: String = "Instruction.Date"
}

trait DateInstructionConstants extends InstructionConstants with DateInstructionSysevents {
  val CfgFSource = "source"
  val CfgFPattern = "pattern"
  val CfgFSourceZone = "sourceZone"
  val CfgFTargetZone = "targetZone"
  val CfgFTargetPattern = "targetPattern"
  val CfgFTargetFmtField = "targetFmtField"
  val CfgFTargetTsField = "targetTsField"

  val default = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
  val default_targetFmtField = "date_fmt"
  val default_targetTsField = "date_ts"
}

object DateInstructionConstants extends DateInstructionConstants


class DateInstruction extends SimpleInstructionBuilder with DateInstructionConstants with WithSyseventPublisher {
  val configId = "date"

  override def simpleInstruction(props: JsValue, id: Option[String] = None): \/[Fail, SimpleInstructionType] =
    for (
      source <- props ~> CfgFSource orFail s"Invalid $configId instruction. Missing '$CfgFSource' value. Contents: ${Json.stringify(props)}";
      targetZone = props ~> CfgFTargetZone;
      zone = props ~> CfgFSourceZone;
      _ <- Try(targetZone.foreach(DateTimeZone.forID)).toOption orFail s"Invalid $configId instruction. Invalid '$CfgFTargetZone' value. Contents: $targetZone";
      _ <- Try(zone.foreach(DateTimeZone.forID)).toOption orFail s"Invalid $configId instruction. Invalid '$CfgFSourceZone' value. Contents: $zone"
    ) yield {

      val pattern = (props ~> CfgFPattern).map(DateTimeFormat.forPattern)

      val zone = props ~> CfgFSourceZone
      val targetZone = props ~> CfgFTargetZone
      var targetPattern = Try((props ~> CfgFTargetPattern).map(DateTimeFormat.forPattern)).getOrElse(Some(DateInstructionConstants.default)) | DateInstructionConstants.default
      val sourcePattern = pattern.map { p =>
        zone match {
          case Some(l) if !l.isEmpty => p.withZone(DateTimeZone.forID(l))
          case None => p
        }
      }
      targetPattern = targetZone match {
        case Some(l) if !l.isEmpty => targetPattern.withZone(DateTimeZone.forID(l))
        case None => targetPattern
      }
      val targetFmtField = props ~> CfgFTargetFmtField | DateInstructionConstants.default_targetFmtField
      val targetTsField = props ~> CfgFTargetTsField | DateInstructionConstants.default_targetTsField

      val uuid = UUIDTools.generateShortUUID

      Built >>('Config -> Json.stringify(props), 'InstructionInstanceId -> uuid)

      fr: EventFrame => {

        val sourceField = macroReplacement(fr, source)


        Try {
          sourcePattern match {
            case Some(p) =>
              val sourceValue = locateFieldValue(fr, sourceField)
              (sourceValue, p.parseDateTime(sourceValue))
            case None =>
              val sourceValue = locateRawFieldValue(fr, sourceField, 0).asNumber.map(_.longValue()) | 0
              (sourceValue, new DateTime(sourceValue))
          }
        }.map { case (s, dt) =>

          val fmt = dt.toString(targetPattern)

          val eventId = fr.eventIdOrNA

          DateParsed >>> Seq('SourceValue -> s, 'SourceDate -> dt, 'ResultFmt -> fmt, 'Ts -> dt.getMillis, 'EventId -> eventId, 'InstructionInstanceId -> uuid)

          List(
            EventValuePath(targetTsField).setLongInto(
              EventValuePath(targetFmtField).setStringInto(fr, fmt), dt.getMillis))

        }.recover {
          case x =>
            UnableToParseDate >>('Source -> fr, 'InstructionInstanceId -> uuid)
            List(fr)
        }.get

      }
    }


}