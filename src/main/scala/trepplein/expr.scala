package trepplein

import trepplein.Level._

import scala.annotation.tailrec
import scala.collection.mutable
import scala.runtime.ScalaRunTime

sealed abstract class BinderInfo extends Product {
  def dump = s"BinderInfo.$productPrefix"
}
object BinderInfo {
  case object Default extends BinderInfo
  case object Implicit extends BinderInfo
  case object StrictImplicit extends BinderInfo
  case object InstImplicit extends BinderInfo
}

case class Binding(prettyName: Name, ty: Expr, info: BinderInfo) {
  def abstr(off: Int, lcs: Vector[LocalConst]): Binding =
    copy(ty = ty.abstr(off, lcs))

  def instantiate(off: Int, es: Vector[Expr]): Binding =
    copy(ty = ty.instantiateReverse(off, es))

  def instantiate(subst: Map[Param, Level]): Binding =
    copy(ty = ty.instantiate(subst))

  def dump(implicit lcs: mutable.Map[LocalConst.Name, String]) =
    s"Binding(${prettyName.dump}, ${ty.dump}, ${info.dump})"

  override val hashCode: Int = prettyName.hashCode + 37 * (ty.hashCode + 37 * info.hashCode)
}

sealed abstract class Expr(val varBound: Int, val hasLocals: Boolean) extends Product {
  def hasVars: Boolean = varBound > 0

  def abstr(lc: LocalConst): Expr = abstr(0, Vector(lc))
  def abstr(off: Int, lcs: Vector[LocalConst]): Expr =
    this match {
      case _ if !hasLocals => this
      case LocalConst(_, name) =>
        lcs.indexWhere(_.name == name) match {
          case -1 => this
          case i => Var(i + off)
        }
      case App(a, b) =>
        App(a.abstr(off, lcs), b.abstr(off, lcs))
      case Lam(domain, body) =>
        Lam(domain.abstr(off, lcs), body.abstr(off + 1, lcs))
      case Pi(domain, body) =>
        Pi(domain.abstr(off, lcs), body.abstr(off + 1, lcs))
      case Let(domain, value, body) =>
        Let(domain.abstr(off, lcs), value.abstr(off, lcs), body.abstr(off + 1, lcs))
    }

  def instantiate(e: Expr): Expr = instantiateReverse(0, Vector(e))
  def instantiateReverse(off: Int, es: Vector[Expr]): Expr =
    this match {
      case _ if varBound <= off => this
      case Var(idx) => if (off <= idx && idx < off + es.size) es(idx - off) else this
      case App(a, b) => App(a.instantiateReverse(off, es), b.instantiateReverse(off, es))
      case Lam(domain, body) => Lam(domain.instantiate(off, es), body.instantiateReverse(off + 1, es))
      case Pi(domain, body) => Pi(domain.instantiate(off, es), body.instantiateReverse(off + 1, es))
      case Let(domain, value, body) => Let(domain.instantiate(off, es), value.instantiateReverse(off, es), body.instantiateReverse(off + 1, es))
    }

  def instantiate(subst: Map[Param, Level]): Expr =
    this match {
      case _ if subst.isEmpty => this
      case v: Var => v
      case Sort(level) => Sort(level.instantiate(subst))
      case Const(name, levels) => Const(name, levels.map(_.instantiate(subst)))
      case LocalConst(of, name) => LocalConst(of.instantiate(subst), name)
      case App(a, b) => App(a.instantiate(subst), b.instantiate(subst))
      case Lam(domain, body) => Lam(domain.instantiate(subst), body.instantiate(subst))
      case Pi(domain, body) => Pi(domain.instantiate(subst), body.instantiate(subst))
      case Let(domain, value, body) => Let(domain.instantiate(subst), value.instantiate(subst), body.instantiate(subst))
    }

  def foreach_(f: Expr => Boolean): Unit =
    if (f(this)) this match {
      case LocalConst(of, _) =>
        of.ty.foreach_(f)
      case App(a, b) =>
        a.foreach_(f)
        b.foreach_(f)
      case Lam(domain, body) =>
        domain.ty.foreach_(f)
        body.foreach_(f)
      case Pi(domain, body) =>
        domain.ty.foreach_(f)
        body.foreach_(f)
      case Let(domain, value, body) =>
        domain.ty.foreach_(f)
        value.foreach_(f)
        body.foreach_(f)
      case _: Var | _: Const | _: Sort =>
    }

  def foreach(f: Expr => Unit): Unit =
    foreach_ { x => f(x); true }

  private def buildSet[T](f: mutable.Set[T] => Unit): Set[T] = {
    val set = mutable.Set[T]()
    f(set)
    set.toSet
  }

  def univParams: Set[Param] =
    buildSet { ps =>
      foreach {
        case Sort(level) => ps ++= level.univParams
        case Const(_, levels) => ps ++= levels.view.flatMap(_.univParams)
        case _ =>
      }
    }

  def constants: Set[Name] =
    buildSet { cs =>
      foreach {
        case Const(name, _) => cs += name
        case _ =>
      }
    }

  def -->:(that: Expr): Expr =
    Pi(Binding(Name.Anon, that, BinderInfo.Default), this)

  override def toString: String = pretty(this)

  def dump(implicit lcs: mutable.Map[LocalConst.Name, String] = null): String =
    this match {
      case _ if lcs eq null =>
        val lcs_ = mutable.Map[LocalConst.Name, String]()
        val d = dump(lcs_)
        if (lcs_.isEmpty) d else {
          val decls = lcs.values.map { n => s"val $n = new LocalConst.Name()\n" }.mkString
          s"{$decls$d}"
        }
      case Var(i) => s"Var($i)"
      case Sort(level) => s"Sort(${level.dump})"
      case Const(name, levels) => s"Const(${name.dump}, Vector(${levels.map(_.dump).mkString(", ")}))"
      case App(a, b) => s"App(${a.dump}, ${b.dump})"
      case Lam(dom, body) => s"Lam(${dom.dump}, ${body.dump})"
      case Pi(dom, body) => s"Pi(${dom.dump}, ${body.dump})"
      case LocalConst(of, name) =>
        val of1 = of.prettyName.toString.replace('.', '_').filter { _.isLetterOrDigit }
        val of2 = if (of1.isEmpty || !of1.head.isLetter) s"n$of1" else of1
        val n = lcs.getOrElseUpdate(name, Stream.from(0).map(i => s"$of2$i").diff(lcs.values.toSeq).head)
        s"LocalConst(${of.dump}, $n)"
      case Let(dom, value, body) => s"Let(${dom.dump}, ${value.dump}, ${body.dump})"
    }
}
case class Var(idx: Int) extends Expr(varBound = idx + 1, hasLocals = false) {
  override def hashCode: Int = idx
}
case class Sort(level: Level) extends Expr(varBound = 0, hasLocals = false) {
  override val hashCode: Int = level.hashCode
}

case class Const(name: Name, levels: Vector[Level]) extends Expr(varBound = 0, hasLocals = false) {
  override val hashCode: Int = 37 * name.hashCode + levels.hashCode
}
case class LocalConst(of: Binding, name: LocalConst.Name = new LocalConst.Name) extends Expr(varBound = 0, hasLocals = true) {
  override val hashCode: Int = 4 + 37 * of.hashCode + name.hashCode
}
case class App(a: Expr, b: Expr)
    extends Expr(
      varBound = math.max(a.varBound, b.varBound),
      hasLocals = a.hasLocals || b.hasLocals
    ) {
  override val hashCode: Int = a.hashCode + 37 * b.hashCode
}
case class Lam(domain: Binding, body: Expr)
    extends Expr(
      varBound = math.max(domain.ty.varBound, body.varBound - 1),
      hasLocals = domain.ty.hasLocals || body.hasLocals
    ) {
  override val hashCode: Int = 1 + 37 * domain.hashCode + body.hashCode
}
case class Pi(domain: Binding, body: Expr)
    extends Expr(
      varBound = math.max(domain.ty.varBound, body.varBound - 1),
      hasLocals = domain.ty.hasLocals || body.hasLocals
    ) {
  override val hashCode: Int = 2 + 37 * domain.hashCode + body.hashCode
}
case class Let(domain: Binding, value: Expr, body: Expr)
    extends Expr(
      varBound = math.max(math.max(domain.ty.varBound, value.varBound), body.varBound - 1),
      hasLocals = domain.ty.hasLocals || value.hasLocals || body.hasLocals
    ) {
  override val hashCode: Int = 3 + 37 * (domain.hashCode + 37 * value.hashCode) + body.hashCode
}

object Sort {
  val Prop = Sort(Level.Zero)
}

object LocalConst {
  final class Name {
    override def toString: String = Integer.toHexString(hashCode()).take(4)
  }
}

object Lam {
  def apply(domain: LocalConst, body: Expr): Expr =
    Lam(domain.of, body.abstr(domain))
}
object Lams {
  def apply(domains: Iterable[LocalConst])(body: Expr): Expr =
    domains.foldRight(body)(Lam(_, _))

  def apply(domains: LocalConst*)(body: Expr): Expr =
    apply(domains)(body)
}

object Pi {
  def apply(domain: LocalConst, body: Expr): Expr =
    Pi(domain.of, body.abstr(domain))
}
object Pis {
  def apply(domains: Iterable[LocalConst])(body: Expr): Expr =
    domains.foldRight(body)(Pi(_, _))

  def apply(domains: LocalConst*)(body: Expr): Expr =
    apply(domains)(body)

  def unapply(e: Expr): Some[(List[LocalConst], Expr)] =
    e match {
      case Pi(dom, expr) =>
        val lc = LocalConst(dom)
        expr.instantiate(lc) match {
          case Pis(lcs, head) =>
            Some((lc :: lcs, head))
        }
      case _ => Some((Nil, e))
    }

  def drop(n: Int, e: Expr): Expr =
    e match {
      case _ if n == 0 => e
      case Pi(_, b) => drop(n - 1, b)
    }

  def instantiate(e: Expr, ts: Seq[Expr]): Expr =
    drop(ts.size, e).instantiateReverse(0, ts.view.reverse.toVector)
}

object Apps {
  @tailrec
  private def decompose(e: Expr, as: List[Expr] = Nil): (Expr, List[Expr]) =
    e match {
      case App(f, a) => decompose(f, a :: as)
      case _ => (e, as)
    }

  def unapply(e: Expr): Some[(Expr, List[Expr])] =
    Some(decompose(e))

  def apply(fn: Expr, as: Iterable[Expr]): Expr =
    as.foldLeft(fn)(App)

  def apply(fn: Expr, as: Expr*): Expr =
    apply(fn, as)
}