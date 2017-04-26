package org.jetbrains.plugins.scala
package lang
package psi
package types

import java.util.Objects

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi._
import com.intellij.psi.util.MethodSignatureUtil
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameters
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScTypeAlias, ScTypeAliasDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScTypedDefinition
import org.jetbrains.plugins.scala.lang.psi.types.api.{Any, PsiTypeParamatersExt, TypeParameter, TypeParameterType}
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaNamesUtil
import org.jetbrains.plugins.scala.project.{ProjectContext, ProjectContextOwner}

import scala.collection.mutable.ArrayBuffer

case class TypeAliasSignature(name: String,
                              typeParams: Seq[TypeParameter],
                              lowerBound: ScType,
                              upperBound: ScType,
                              isDefinition: Boolean,
                              ta: ScTypeAlias) extends ProjectContextOwner {

  override implicit def projectContext: ProjectContext = ta.projectContext

  def updateTypes(fun: ScType => ScType): TypeAliasSignature = TypeAliasSignature(name,
    typeParams.map(_.update(fun)),
    fun(lowerBound),
    fun(upperBound),
    isDefinition,
    ta)
    .copyWithCompoundBody

  def updateTypesWithVariance(function: (ScType, Int) => ScType, variance: Int): TypeAliasSignature = TypeAliasSignature(name,
    typeParams.map(_.updateWithVariance(function, variance)),
    function(lowerBound, variance),
    function(upperBound, -variance),
    isDefinition,
    ta)
    .copyWithCompoundBody

  private def copyWithCompoundBody = copy(ta = ScTypeAlias.getCompoundCopy(this, ta))

  def canEqual(other: Any): Boolean = other.isInstanceOf[TypeAliasSignature]

  override def equals(other: Any): Boolean = other match {
    case that: TypeAliasSignature =>
      (that canEqual this) &&
        name == that.name &&
        typeParams == that.typeParams &&
        lowerBound == that.lowerBound &&
        upperBound == that.upperBound &&
        isDefinition == that.isDefinition
    case _ => false
  }

  override def hashCode(): Int =
    Objects.hash(name, typeParams, lowerBound, upperBound, Boolean.box(isDefinition))

  def getType: Option[ScType] = ta match {
    case definition: ScTypeAliasDefinition => definition.aliasedType.toOption
    case _ => None
  }
}

object TypeAliasSignature {
  def apply(typeAlias: ScTypeAlias): TypeAliasSignature = TypeAliasSignature(typeAlias.name,
    typeAlias.typeParameters.map(TypeParameter(_)),
    typeAlias.lowerBound.getOrNothing,
    typeAlias.upperBound.getOrAny,
    typeAlias.isDefinition,
    typeAlias)
}

class Signature(val name: String, private val typesEval: List[Seq[() => ScType]], val paramLength: List[Int],
                private val tParams: Seq[TypeParameter], val substitutor: ScSubstitutor,
                val namedElement: PsiNamedElement, val hasRepeatedParam: Seq[Int] = Seq.empty) extends ProjectContextOwner {

  override implicit def projectContext: ProjectContext = namedElement

  def this(name: String, stream: Seq[() => ScType], paramLength: Int, substitutor: ScSubstitutor,
           namedElement: PsiNamedElement) =
    this(name, List(stream), List(paramLength), Seq.empty, substitutor, namedElement)

  private def types: List[Seq[() => ScType]] = typesEval

  def substitutedTypes: List[Seq[() => ScType]] = types.map(_.map(f => () => substitutor.subst(f()).unpackedType))

  def typeParams: Seq[TypeParameter] = tParams.map(_.update(substitutor.subst))

  def equiv(other: Signature): Boolean = {
    def fieldCheck(other: Signature): Boolean = {
      def isField(s: Signature) = s.namedElement.isInstanceOf[PsiField]
      !isField(this) ^ isField(other)
    }

    ScalaNamesUtil.equivalent(name, other.name) &&
            ((typeParams.length == other.typeParams.length && paramTypesEquiv(other)) || 
              (paramLength == other.paramLength && javaErasedEquiv(other))) && fieldCheck(other)
    
  }

  def javaErasedEquiv(other: Signature): Boolean = {
    (this, other) match {
      case (ps1: PhysicalSignature, ps2: PhysicalSignature) if ps1.isJava && ps2.isJava =>
        implicit val elementScope = ps1.method.elementScope
        val psiSub1 = ScalaPsiUtil.getPsiSubstitutor(ps1.substitutor)
        val psiSub2 = ScalaPsiUtil.getPsiSubstitutor(ps2.substitutor)
        val psiSig1 = ps1.method.getSignature(psiSub1)
        val psiSig2 = ps2.method.getSignature(psiSub2)
        MethodSignatureUtil.METHOD_PARAMETERS_ERASURE_EQUALITY.equals(psiSig1, psiSig2)
      case _ => false
    }
  }

  def paramTypesEquiv(other: Signature): Boolean = {
    paramTypesEquivExtended(other, ScUndefinedSubstitutor(), falseUndef = true)._1
  }


  def paramTypesEquivExtended(other: Signature, uSubst: ScUndefinedSubstitutor,
                              falseUndef: Boolean): (Boolean, ScUndefinedSubstitutor) = {
    import org.jetbrains.plugins.scala.lang.psi.types.Signature._

    var undefSubst = uSubst
    if (paramLength != other.paramLength && !(paramLength.sum == 0 && other.paramLength.sum == 0)) return (false, undefSubst)
    if (hasRepeatedParam != other.hasRepeatedParam) return (false, undefSubst)
    val unified1 = unify(substitutor, typeParams, typeParams)
    val unified2 = unify(other.substitutor, typeParams, other.typeParams)
    val clauseIterator = substitutedTypes.iterator
    val otherClauseIterator = other.substitutedTypes.iterator
    while (clauseIterator.hasNext && otherClauseIterator.hasNext) {
      val clause1 = clauseIterator.next()
      val clause2 = otherClauseIterator.next()
      val typesIterator = clause1.iterator
      val otherTypesIterator = clause2.iterator
      while (typesIterator.hasNext && otherTypesIterator.hasNext) {
        val t1 = typesIterator.next()
        val t2 = otherTypesIterator.next()
        val tp2 = unified2.subst(t2())
        val tp1 = unified1.subst(t1())
        var t = tp2.equiv(tp1, undefSubst, falseUndef)
        if (!t._1 && tp1.equiv(api.AnyRef) && this.isJava) {
          t = tp2.equiv(Any, undefSubst, falseUndef)
        }
        if (!t._1 && tp2.equiv(api.AnyRef) && other.isJava) {
          t = Any.equiv(tp1, undefSubst, falseUndef)
        }
        if (!t._1) {
          return (false, undefSubst)
        }
        undefSubst = t._2
      }
    }
    (true, undefSubst)
  }

  override def equals(that: Any): Boolean = that match {
    case s: Signature => equiv(s) && parameterlessKind == s.parameterlessKind
    case _ => false
  }

  def parameterlessKind: Int = {
    namedElement match {
      case f: ScFunction if !f.hasParameterClause => 1
      case _: PsiMethod => 2
      case _ => 3
    }
  }

  override def hashCode: Int = simpleHashCode * 31 + parameterlessKind


  /**
   * Use it, while building class hierarchy.
   * Because for class hierarch def foo(): Int is the same thing as def foo: Int and val foo: Int.
   */
  def simpleHashCode: Int = {
    ScalaNamesUtil.clean(name).hashCode
  }

  def isJava: Boolean = false

  def parameterlessCompatible(other: Signature): Boolean = {
    (namedElement, other.namedElement) match {
      case (f1: ScFunction, f2: ScFunction) =>
        !f1.hasParameterClause ^ f2.hasParameterClause
      case (f1: ScFunction, _: PsiMethod) => f1.hasParameterClause
      case (_: PsiMethod, f2: ScFunction) => f2.hasParameterClause
      case (_: PsiMethod, _: PsiMethod) => true
      case (_: PsiMethod, _) => false
      case (_, f: ScFunction)  => !f.hasParameterClause
      case (_, _: PsiMethod) => false
      case _ => true
    }
  }
}

object Signature {
  def apply(function: ScFunction) = new Signature(
    function.name,
    PhysicalSignature.typesEval(function),
    PhysicalSignature.paramLength(function),
    function.getTypeParameters.instantiate,
    ScSubstitutor.empty,
    function,
    PhysicalSignature.hasRepeatedParam(function)
  )

  def getter(definition: ScTypedDefinition) = new Signature(
    definition.name,
    Seq.empty,
    0,
    ScSubstitutor.empty,
    definition
  )

  def setter(definition: ScTypedDefinition) = new Signature(
    s"$definition.name_=",
    Seq(() => definition.getType().getOrAny),
    1,
    ScSubstitutor.empty,
    definition
  )

  def unify(subst: ScSubstitutor, tps1: Seq[TypeParameter], tps2: Seq[TypeParameter]): ScSubstitutor = {
    var result = subst
    val iterator1 = tps1.iterator
    val iterator2 = tps2.iterator
    while (iterator1.hasNext && iterator2.hasNext) {
      result = result.bindT(iterator2.next().nameAndId, TypeParameterType(iterator1.next()))
    }
    result
  }
}



import com.intellij.psi.PsiMethod
object PhysicalSignature {
  def typesEval(method: PsiMethod): List[Seq[() => ScType]] = method match {
    case fun: ScFunction =>
      fun.effectiveParameterClauses.map(clause => ScalaPsiUtil.mapToLazyTypesSeq(clause.effectiveParameters)).toList
    case _ => List(ScalaPsiUtil.mapToLazyTypesSeq(method.getParameterList match {
      case p: ScParameters => p.params
      case p => p.getParameters.toSeq
    }))
  }

  def paramLength(method: PsiMethod): List[Int] = method match {
    case fun: ScFunction => fun.effectiveParameterClauses.map(_.effectiveParameters.length).toList
    case _ => List(method.getParameterList.getParametersCount)
  }

  def hasRepeatedParam(method: PsiMethod): Seq[Int] = {
    method.getParameterList match {
      case p: ScParameters =>
        val params = p.params
        val res = new ArrayBuffer[Int]()
        var i = 0
        while (i < params.length) {
          if (params(i).isRepeatedParameter) res += i
          i += 1
        }
        res
      case p =>
        val parameters = p.getParameters
        if (parameters.isEmpty) return Seq.empty
        if (parameters(parameters.length - 1).isVarArgs) return Seq(parameters.length - 1)
        Seq.empty
    }
  }

  def unapply(signature: PhysicalSignature): Option[(PsiMethod, ScSubstitutor)] = {
    Some(signature.method, signature.substitutor)
  }
}

class PhysicalSignature(val method: PsiMethod, override val substitutor: ScSubstitutor)
        extends Signature(method.name, PhysicalSignature.typesEval(method), PhysicalSignature.paramLength(method),
          method.getTypeParameters.instantiate, substitutor, method, PhysicalSignature.hasRepeatedParam(method)) {
  override def isJava: Boolean = method.getLanguage == JavaFileType.INSTANCE.getLanguage
}
