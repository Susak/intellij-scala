package org.jetbrains.plugins.scala.codeInspection.internal

import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.{HighlightInfoHolder, HighlightVisitorImpl}
import com.intellij.codeInspection._
import com.intellij.lang.ASTNode
import com.intellij.lang.annotation.{Annotation, AnnotationHolder, AnnotationSession, HighlightSeverity}
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiElementVisitor, PsiJavaFile}
import org.jetbrains.plugins.scala.annotator.ScalaAnnotator
import org.jetbrains.plugins.scala.project.ProjectContext

/**
 * @author Alexander Podkhalyuzin
 */
class AnnotatorBasedErrorInspection extends LocalInspectionTool {
  override def getGroupDisplayName: String = "Internal"

  override def getGroupPath: Array[String] = Array("Scala", "Internal")

  override def getDisplayName: String = "Annotator Based Error Inspection"

  override def buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = {
    new PsiElementVisitor {
      override def visitElement(element: PsiElement) {
        val file = element.getContainingFile
        if (file.isInstanceOf[PsiJavaFile]) {
          val highlightVisitors = Extensions.getExtensions(HighlightVisitor.EP_HIGHLIGHT_VISITOR, element.getProject)
          val highlightInfoHolder = new HighlightInfoHolder(file)

          highlightVisitors.headOption.map {
            case vr: HighlightVisitorImpl =>
              vr.clone().analyze(file, true, highlightInfoHolder, new Runnable {
                def run() {
                  vr.visit(element)
                }
              })
            case _ =>
          }

          if (highlightInfoHolder.hasErrorResults) {
            holder.registerProblem(element, "Error detected", ProblemHighlightType.ERROR, null: TextRange)
          }
        } else {
          val annotator = new ScalaAnnotator {
            override def isAdvancedHighlightingEnabled(element: PsiElement): Boolean = true

            override implicit def projectContext: ProjectContext = holder.getProject
          }
          val FakeAnnotation = new com.intellij.lang.annotation.Annotation(
            0, 0, HighlightSeverity.WEAK_WARNING, "message", "tooltip")
          val annotationHolder = new AnnotationHolder {
            override def createAnnotation(severity: HighlightSeverity, range: TextRange, message: String,
                                          htmlTooltip: String): Annotation = FakeAnnotation

            def createAnnotation(severity: HighlightSeverity, range: TextRange, str: String) = FakeAnnotation
            
            def isBatchMode: Boolean = false

            def createInfoAnnotation(range: TextRange, message: String): Annotation = FakeAnnotation

            def createInfoAnnotation(node: ASTNode, message: String): Annotation = FakeAnnotation

            def createInfoAnnotation(elt: PsiElement, message: String): Annotation = FakeAnnotation

            def createInformationAnnotation(range: TextRange, message: String): Annotation = FakeAnnotation

            def createInformationAnnotation(node: ASTNode, message: String): Annotation = FakeAnnotation

            def createInformationAnnotation(elt: PsiElement, message: String): Annotation = FakeAnnotation

            def createWarningAnnotation(range: TextRange, message: String): Annotation = {
              holder.registerProblem(element, s"Warning: $message", ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
              FakeAnnotation
            }

            def createWarningAnnotation(node: ASTNode, message: String): Annotation = {
              holder.registerProblem(element, s"Warning: $message", ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
              FakeAnnotation
            }

            def createWarningAnnotation(elt: PsiElement, message: String): Annotation = {
              holder.registerProblem(element, s"Warning: $message", ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
              FakeAnnotation
            }

            def createErrorAnnotation(range: TextRange, message: String): Annotation = {
              if (message != null) {
                holder.registerProblem(element, s"Error detected: $message", ProblemHighlightType.ERROR)
              }
              FakeAnnotation
            }

            def createErrorAnnotation(node: ASTNode, message: String): Annotation = {
              if (message != null) {
                holder.registerProblem(element, s"Error detected: $message", ProblemHighlightType.ERROR)
              }
              FakeAnnotation
            }

            def createErrorAnnotation(elt: PsiElement, message: String): Annotation = {
              if (message != null) {
                holder.registerProblem(element, s"Error detected: $message", ProblemHighlightType.ERROR)
              }
              FakeAnnotation
            }

            def getCurrentAnnotationSession: AnnotationSession = {
              new AnnotationSession(element.getContainingFile)
            }

            def createWeakWarningAnnotation(p1: TextRange, p2: String): Annotation = FakeAnnotation

            def createWeakWarningAnnotation(p1: ASTNode, p2: String): Annotation = FakeAnnotation

            def createWeakWarningAnnotation(p1: PsiElement, p2: String): Annotation = FakeAnnotation
          }
          annotator.annotate(element, annotationHolder)
        }
      }
    }
  }
}