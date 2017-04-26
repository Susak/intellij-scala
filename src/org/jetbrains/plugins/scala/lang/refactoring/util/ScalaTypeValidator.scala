package org.jetbrains.plugins.scala.lang.refactoring.util

import com.intellij.psi._
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiTreeUtil.getParentOfType
import org.jetbrains.plugins.scala.ScalaBundle
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScTypeParam
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition
import org.jetbrains.plugins.scala.lang.resolve.ResolveTargets
import org.jetbrains.plugins.scala.lang.resolve.ResolveTargets._
import org.jetbrains.plugins.scala.lang.resolve.processor.BaseProcessor
import org.jetbrains.plugins.scala.project.ProjectContext

import scala.collection.mutable

/**
  * Created by Kate Ustyuzhanina
  * on 8/3/15
  */
class ScalaTypeValidator(val selectedElement: PsiElement, override val noOccurrences: Boolean, enclosingContainerAll: PsiElement, enclosingOne: PsiElement)
  extends ScalaValidator(selectedElement, noOccurrences, enclosingContainerAll, enclosingOne) {

  private implicit def ctx: ProjectContext = selectedElement

  protected override def findConflictsImpl(name: String, allOcc: Boolean): Seq[(PsiNamedElement, String)] = {
    //returns declaration and message
    val container = enclosingContainer(allOcc)
    if (container == null) return Seq.empty

    forbiddenNames(container, name) match {
      case Seq() => forbiddenNamesInBlock(container, name)
      case seq => seq
    }
  }

  import ScalaTypeValidator._

  protected def forbiddenNames(position: PsiElement, name: String): Seq[(PsiNamedElement, String)] = {
    val result = mutable.ArrayBuffer.empty[(PsiNamedElement, String)]

    val processor = new BaseProcessor(ValueSet(ResolveTargets.CLASS)) {
      override def execute(element: PsiElement, state: ResolveState): Boolean = {
        result ++= zipWithMessage(element, name)
        true
      }
    }
    PsiTreeUtil.treeWalkUp(processor, position, null, ResolveState.initial())

    result
  }

  protected def forbiddenNamesInBlock(commonParent: PsiElement, name: String): Seq[(PsiNamedElement, String)] = {
    val result = mutable.ArrayBuffer.empty[(PsiNamedElement, String)]

    commonParent.depthFirst().foreach {
      result ++= zipWithMessage(_, name)
    }

    result
  }

  override def validateName(name: String): String =
    super.validateName(name.capitalize)
}

object ScalaTypeValidator {

  def empty =
    new ScalaTypeValidator(null, noOccurrences = true, null, null) {
      override def validateName(name: String): String = name
    }

  def apply(element: PsiElement, container: PsiElement, noOccurrences: Boolean) =
    new ScalaTypeValidator(element, noOccurrences, container, container)

  private def zipWithMessage(element: PsiElement, name: String): Option[(PsiNamedElement, String)] =
    Option(element).collect {
      case named: PsiNamedElement if named.getName == name => named
    }.collect {
      case named@(_: ScTypeAlias | _: ScTypeParam) => (named, "type")
      case typeDefinition: ScTypeDefinition if getParentOfType(typeDefinition, classOf[ScFunctionDefinition]) == null =>
        (typeDefinition, "class")
    }.map {
      case (named, kind) => (named, message(kind, name))
    }

  private[this] def message(kind: String, name: String) =
    ScalaBundle.message(s"introduced.typeAlias.will.conflict.with.$kind.name", name)
}
