package eventstreams.support

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

import java.io._
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.zip.GZIPOutputStream

import com.typesafe.scalalogging.StrictLogging
import core.sysevents.WithSyseventPublisher
import core.sysevents.SyseventOps.symbolToSyseventOps
import core.sysevents.ref.ComponentWithBaseSysevents
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers}

trait TempFolderSysevents extends ComponentWithBaseSysevents with StrictLogging {
  override def componentId: String = "Test.TempFolder"

  val FailedToDelete = 'FailedToDeleteFile.info
  val Rolled = 'Rolled.info
  val Cleaned = 'Cleaned.info
  val CreatedDir = 'CreatedDir.info
  val CreatedFile = 'CreatedFile.info
  val NewInputIntoFile = 'NewInputIntoFile.info

}

trait TempFolder extends BeforeAndAfterEach with BeforeAndAfterAll with Matchers with TempFolderSysevents with WithSyseventPublisher {
  this: org.scalatest.Suite =>

  case class OpenFile(f: java.io.File, charset: Charset = Charset.forName("UTF-8"), append: Boolean = false) {
    var writer: Option[BufferedWriter] = None

    def openAppend() =
      writer = Some(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, true), charset)))
    def openNew() =
      writer = Some(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, false), charset)))

    def close() = {
      writer.foreach{ w => w.close() }
      writer = None
    }

    def write(s: String) = writer.foreach { w =>
      w.write(s)
      w.flush()
      NewInputIntoFile >> ('Contents -> s)
      Thread.sleep(5)
    }



    def delete(name: String) = deleteFile(new File(tempDirPath + "/" + name), truncateOnly = false) shouldBe true

    private def deleteFile(f: File, truncateOnly: Boolean = true) = {
      def tryDelete(attempt: Int): Boolean = f.delete() match {
        case false if attempt < 50 =>
          logger.error("Failed to delete file " + f.getAbsolutePath +" after " + attempt + " attempt(s), exists: " + f.exists())
          FailedToDelete >> ('Message -> ("Failed to delete file " + f.getAbsolutePath +" after " + attempt + " attempt(s)"))
          Thread.sleep(65)
          tryDelete(attempt + 1)
        case x => x
      }
      if (truncateOnly) {
        val s = new FileOutputStream(f)
        s.getChannel.truncate(0)
        val result = s.getChannel.size() == 0
        s.close()
        result
      } else  tryDelete(1)
    }

    def rollGz(name: String) =  {
      close()
      val is = new FileInputStream(f)
      val os = new FileOutputStream(tempDirPath + "/" + name)
      val gz = new GZIPOutputStream(os)
      val buffer = ByteBuffer.allocate(1024 * 16).array()
      var len = 0
      do {
        len = is.read(buffer)
        if (len > -1) {
          gz.write(buffer,0, len)
        }
      } while (len > -1)
      gz.close()
      os.close()
      is.close()
      Thread.sleep(5)

      writer.foreach(_.close())
      deleteFile(f) shouldBe true
//      f.createNewFile() shouldBe true
      openNew()
      write("")
      Rolled >> ('Message -> s"$f rolled into ${tempDirPath + "/" + name}")

    }

    def rollOpen(name: String) =  {
      close()
      val is = new FileInputStream(f)
      val os = new FileOutputStream(tempDirPath + "/" + name)
      val buffer = ByteBuffer.allocate(1024 * 16).array()
      var len = 0
      do {
        len = is.read(buffer)
        if (len > -1) {
          os.write(buffer,0, len)
        }
      } while (len > -1)
      os.close()
      is.close()
      Thread.sleep(5)

      writer.foreach(_.close())
      deleteFile(f) shouldBe true
//      f.createNewFile() shouldBe true
      openNew()
      write("")
    }

    if (append) openAppend() else openNew()
  }

  override def afterEach(): Unit = {
    Option(tempDir.listFiles).map(_.toList).getOrElse(Nil).foreach(_.delete)
    Cleaned >> ('Message -> s"Cleaned files under ${tempDir.getPath}")
    super.afterEach()
  }


  override def afterAll(): Unit = {
    delete()
    super.afterAll()
  }

  lazy val tempDir = {
    val dir = java.io.File.createTempFile("test", "")
    dir.delete
    CreatedDir >> ('Message -> s"Created $dir")
    dir.mkdir
    dir
  }

  def tempDirPath = tempDir.getPath

  def withNewFile(name: String)(f: OpenFile => Unit) = {
    val openFile = OpenFile(createNewFile(name))
    try {
      f(openFile)
    } finally {
      openFile.close()
    }
  }

  def withExistingFile(name: String)(f: OpenFile => Unit) = {
    val openFile = OpenFile(createNewFile(name), append = true)
    try {
      f(openFile)
    } finally {
      openFile.close()
    }
  }


  def createNewFile(name: String) = {
    val f = new java.io.File(tempDir.getPath+"/"+name)
    CreatedFile >> ('Message -> s"Created ${tempDir.getPath+"/"+name}")
    f.createNewFile
    f
  }

  def delete() = {
    Option(tempDir.listFiles).map(_.toList).getOrElse(Nil).foreach(_.delete)
    tempDir.delete
    Cleaned >> ('Message -> s"Cleaned files and directory ${tempDir.getPath}")

  }

}
