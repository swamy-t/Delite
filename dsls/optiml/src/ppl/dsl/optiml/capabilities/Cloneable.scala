package ppl.dsl.optiml.capabilities

import scala.virtualization.lms.common.{Variables, Base}
import ppl.dsl.optiml.datastruct.scala.{Vector,Matrix}
import ppl.dsl.optiml.{OptiMLExp, OptiML}

trait CloneableInternal[Rep[X],T] {
  def cloneL(lhs: Rep[T]) : Rep[T]
}

trait CloneableOps extends Variables {
  this: OptiML =>

  type Cloneable[X] = CloneableInternal[Rep,X]
  
  implicit def cloneableToCloneableOps[T:Cloneable:Manifest](n: T) = new CloneableOpsCls(unit(n))
  implicit def repCloneableToCloneableOps[T:Cloneable:Manifest](n: Rep[T]) = new CloneableOpsCls(n)
  implicit def varCloneableToCloneableOps[T:Cloneable:Manifest](n: Var[T]) = new CloneableOpsCls(readVar(n))

  class CloneableOpsCls[T](lhs: Rep[T])(implicit mT: Manifest[T], cloneable: Cloneable[T]){
    def cloneL() = cloneable.cloneL(lhs)
  }
  
  implicit def vectorCloneable[T:Manifest]: Cloneable[Vector[T]] = new Cloneable[Vector[T]] {
    def cloneL(lhs: Rep[Vector[T]]) = lhs.cloneL()
  }
  
  implicit def matrixCloneable[T:Manifest]: Cloneable[Matrix[T]] = new Cloneable[Matrix[T]] {
    def cloneL(lhs: Rep[Matrix[T]]) = lhs.cloneL()
  }

  implicit def numericCloneable[T:Numeric]: Cloneable[T] = new Cloneable[T] {
    def cloneL(lhs: Rep[T]) = lhs
  }  
}

