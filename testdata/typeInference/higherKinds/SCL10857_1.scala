object ListC {
  class T1[A]
  class T2[A]
  implicit def getT[A]: T1[A] with T2[A] = null
}

class Test {
  import ListC._
  def foo[T[_], A](implicit a: T[A]): T[A] = a
  val bar: T1[Int] = /*start*/foo/*end*/
}
//ListC.T1[Int]