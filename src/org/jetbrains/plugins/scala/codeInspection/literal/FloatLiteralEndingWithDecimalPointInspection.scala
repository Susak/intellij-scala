package org.jetbrains.plugins.scala
package codeInspection
package literal

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.lang.psi.api.base.ScLiteral
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory.createExpressionFromText

class FloatLiteralEndingWithDecimalPointInspection extends AbstractInspection("FloatLiteralEndingWithDecimalPoint", "Floating point literal ending with '.'"){
  def actionFor(holder: ProblemsHolder): PartialFunction[PsiElement, Any] = {
    case lit: ScLiteral if lit.getText.endsWith(".") =>
      holder.registerProblem(lit, getDisplayName, new MakeDoubleFix(lit), new MakeFloatFix(lit), new AddZeroAfterDecimalPoint(lit))
  }
}

class MakeDoubleFix(lit: ScLiteral) extends AbstractFixOnPsiElement("Convert to %s".format(lit.getText.dropRight(1) + "d"), lit) {
  def doApplyFix(project: Project) {
    val l = getElement
    l.replace(createExpressionFromText(l.getText.dropRight(1) + "d")(l.getManager))
  }
}

class MakeFloatFix(lit: ScLiteral) extends AbstractFixOnPsiElement("Convert to %s".format(lit.getText.dropRight(1) + "f"), lit) {
  def doApplyFix(project: Project) {
    val l = getElement
    l.replace(createExpressionFromText(l.getText.dropRight(1) + "f")(l.getManager))
  }
}

class AddZeroAfterDecimalPoint(lit: ScLiteral) extends AbstractFixOnPsiElement("Convert to %s".format(lit.getText + "0"), lit) {
  def doApplyFix(project: Project) {
    val l = getElement
    l.replace(createExpressionFromText(l.getText + "0")(l.getManager))
  }
}
