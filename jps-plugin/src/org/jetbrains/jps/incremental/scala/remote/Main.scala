package org.jetbrains.jps.incremental.scala
package remote

import java.io._
import java.util.{Timer, TimerTask}

import com.martiansoftware.nailgun.NGContext
import org.jetbrains.jps.incremental.messages.BuildMessage.Kind
import org.jetbrains.jps.incremental.scala.local.LocalServer
import org.jetbrains.jps.incremental.scala.local.worksheet.WorksheetServer

/**
 * @author Pavel Fatin
 * @author Dmitry Naydanov         
 */
object Main extends Base64User {
  private val Server = new LocalServer()
  private val worksheetServer = new WorksheetServer

  private var shutdownTimer: Timer = _

  def nailMain(context: NGContext) {
    cancelShutdown()
    make(context.getArgs.toSeq, context.out, standalone = false)
    resetShutdownTimer(context)
  }
  
  def main(args: Array[String]) {
    make(args, System.out, standalone = true)
  }
  
  private def make(arguments: Seq[String], out: PrintStream, standalone: Boolean) {
    val client = new MyEventGeneratingClient(standalone, out)

    val oldOut = System.out
    // Suppress any stdout data, interpret such data as error
    System.setOut(System.err)
    
    try {
      val args = {
        val strings = arguments.map {
          arg => 
            val s = new String(decodeBase64(arg.getBytes), "UTF-8")
            if (s == "#STUB#") "" else s
        }
        Arguments.from(strings)
      }

      if (!worksheetServer.isRepl(args)) Server.compile(args.sbtData, args.compilerData, args.compilationData, client)

      if (!client.hadErrors) worksheetServer.loadAndRun(args, out, client, standalone)
    } catch {
      case e: Throwable => 
        client.trace(e)
    } finally {
      System.setOut(oldOut)
    }
  }

  private def cancelShutdown() = {
    if (shutdownTimer != null) shutdownTimer.cancel()
  }

  private def resetShutdownTimer(context: NGContext) {
    val delay = Option(System.getProperty("shutdown.delay")).map(_.toInt)
    delay.foreach { t =>
      val delayMs = t * 60 * 1000
      val shutdownTask = new TimerTask {
        override def run(): Unit = context.getNGServer.shutdown(true)
      }
      shutdownTimer = new Timer()
      shutdownTimer.schedule(shutdownTask, delayMs)
    }
  }
  
  class MyEventGeneratingClient(standalone: Boolean, out: PrintStream) extends EventGeneratingClient(
    (event: Event) => {
      val encode = encodeBase64(event.toBytes)
      out.write((if (standalone && !encode.endsWith("=")) encode + "=" else encode).getBytes)
    }, 
    out.checkError
  ) {
    private var hasErrors = false

    def hadErrors: Boolean = hasErrors
    
    override def error(text: String, source: Option[File], line: Option[Long], column: Option[Long]) {
      hasErrors = true
      super.error(text, source, line, column)
    }

    override def message(kind: Kind, text: String, source: Option[File], line: Option[Long], column: Option[Long]) {
      if (kind == Kind.ERROR) hasErrors = true
      super.message(kind, text, source, line, column)
    }
  }
}
