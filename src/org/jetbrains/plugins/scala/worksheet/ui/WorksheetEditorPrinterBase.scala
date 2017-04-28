package org.jetbrains.plugins.scala.worksheet.ui

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.{Document, Editor, VisualPosition}
import com.intellij.openapi.editor.impl.FoldingModelImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.jps.incremental.scala.remote.ServerException
import org.jetbrains.jps.incremental.scala.remote.worksheet.WorksheetExceptionHandler
import org.jetbrains.plugins.scala.extensions
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings
import org.jetbrains.plugins.scala.worksheet.actions.WorksheetFileHook
import org.jetbrains.plugins.scala.worksheet.ui.WorksheetDiffSplitters.SimpleWorksheetSplitter

/**
  * User: Dmitry.Naydanov
  * Date: 03.02.17.
  */
abstract class WorksheetEditorPrinterBase(protected val originalEditor: Editor, 
                                          protected val worksheetViewer: Editor) {
  protected val viewerFolding: FoldingModelImpl = worksheetViewer.getFoldingModel.asInstanceOf[FoldingModelImpl]
  protected val project: Project = originalEditor.getProject
  protected val originalDocument: Document = originalEditor.getDocument
  protected val viewerDocument: Document = worksheetViewer.getDocument
  
  protected lazy val group = new WorksheetFoldGroup(worksheetViewer, originalEditor, project, getWorksheetSplitter.orNull)
  
  private var inited = false
  
  def getViewerEditor: Editor = worksheetViewer

  def getScalaFile: ScalaFile
  
  def processLine(line: String): Boolean
  
  def flushBuffer(): Unit

  def scheduleWorksheetUpdate(): Unit

  def internalError(error: Throwable) {
    def printError(throwable: Throwable, stackTrace: Array[_]) = simpleUpdate (
      s"Internal error: ${throwable.getClass.getSimpleName} : ${throwable.getMessage.stripPrefix(WorksheetExceptionHandler.RERUN_MARKER)}\n${stackTrace mkString "\n"}", 
      viewerDocument
    )
    
    extensions.invokeLater {
      extensions.inWriteAction {
        error match {
          case we: ServerException =>
            printError(we, we.getLines)
            if (shouldRerun(we)) offerRerun()
          case other =>
            printError(other, other.getStackTrace)
        }
      }
    }
  }
  
  protected def getWorksheetSplitter: Option[SimpleWorksheetSplitter] = 
    Option(worksheetViewer.getUserData(WorksheetEditorPrinterFactory.DIFF_SPLITTER_KEY))
  
  protected def getWorksheetViewersRation: Float = 
    getWorksheetSplitter.map(_.getProportion).getOrElse(WorksheetEditorPrinterFactory.DEFAULT_WORKSHEET_VIEWERS_RATIO)
  
  protected def redrawViewerDiffs() {
    getWorksheetSplitter.foreach(_.redrawDiffs())
  }
  
  protected def saveEvaluationResult(result: String) {
    WorksheetEditorPrinterFactory.saveWorksheetEvaluation(getScalaFile, result, getWorksheetViewersRation)
    redrawViewerDiffs()
  }
  
  protected def shouldRerun(ex: ServerException): Boolean = ex.getMessage.startsWith(WorksheetExceptionHandler.RERUN_MARKER)
  
  protected def offerRerun() {
    WorksheetFileHook.instance(project).installRerunAction(getScalaFile)
  }
  
  protected def cleanFoldings() {
    extensions.invokeLater {
      viewerFolding runBatchFoldingOperation new Runnable {
        override def run() {
          viewerFolding.clearFoldRegions()
        }
      }
      
      worksheetViewer.getCaretModel.moveToVisualPosition(new VisualPosition(0, 0))
    }
  }

  /**
    * 
    * @param foldings : (Start output, End output, Input lines count, End input)*
    */
  protected def updateFoldings(foldings: Seq[(Int, Int, Int, Int)]) {
    CommandProcessor.getInstance().executeCommand(project, new Runnable {
      override def run() {
        viewerFolding runBatchFoldingOperation(new Runnable {
          override def run() {
            foldings map {
              case (start, end, limit, originalEnd) =>
                val offset = originalDocument getLineEndOffset java.lang.Math.min(originalEnd, originalDocument.getLineCount)
                val linesCount = viewerDocument.getLineNumber(end) - start - limit + 1

                new WorksheetFoldRegionDelegate(
                  worksheetViewer, viewerDocument.getLineStartOffset(start + limit - 1), end,
                  offset, linesCount, group, limit
                )
            } foreach (region => viewerFolding addFoldRegion region)

            WorksheetFoldGroup.save(getScalaFile, group)
          }
        }, false)
      }
    }, null, null)
  }
  
  protected def isInited: Boolean = inited
  
  protected def init() {
    inited = true

    val oldSync = originalEditor getUserData WorksheetEditorPrinterFactory.DIFF_SYNC_SUPPORT
    if (oldSync != null) oldSync.dispose()

    WorksheetEditorPrinterFactory.synch(originalEditor, worksheetViewer, getWorksheetSplitter, Some(group))
    
    cleanFoldings()
  }
  
  protected def getNewLines(count: Int): String = StringUtil.repeatSymbol('\n', count)
  
  protected def commitDocument(doc: Document) {
    if (project.isDisposed) return //EA-70786
    PsiDocumentManager getInstance project commitDocument doc
  }
  
  protected def simpleUpdate(text: String, document: Document) {
    document setText text
    commitDocument(document)
  }
  
  protected def simpleAppend(text: String, document: Document) {
    document.insertString(document.getTextLength, text)
    commitDocument(document)
  }
  
  protected def getOutputLimit: Int = ScalaProjectSettings.getInstance(project).getOutputLimit
}
