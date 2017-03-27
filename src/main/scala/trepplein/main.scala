package trepplein

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

class LibraryPrinter(env: PreEnvironment, notations: Map[Name, Notation],
    out: String => Unit,
    prettyOptions: PrettyOptions,
    lineWidth: Int = 80,
    printDependencies: Boolean = true) {
  private val declsPrinted = mutable.Map[Name, Unit]()
  def printDecl(name: Name): Unit = declsPrinted.getOrElseUpdate(name, {
    val tc = new TypeChecker(env, unsafeUnchecked = true)
    val pp = new PrettyPrinter(typeChecker = Some(tc), notations = notations, options = prettyOptions)

    val decl = env(name)
    if (printDependencies) {
      decl.ty.constants.foreach(printDecl)
      decl match {
        case decl: Definition if !prettyOptions.hideProofs || !tc.isProposition(decl.ty) =>
          decl.value.constants.foreach(printDecl)
        case _ =>
      }
    }

    out((pp.pp(decl) <> Doc.line).render(lineWidth))
  })

  private val axiomsChecked = mutable.Map[Name, Unit]()
  def checkAxioms(name: Name): Unit = axiomsChecked.getOrElseUpdate(name, env(name) match {
    case Definition(_, _, ty, value, _) =>
      ty.constants.foreach(checkAxioms)
      value.constants.foreach(checkAxioms)
    case Axiom(_, _, _) =>
      printDecl(name)
    // TODO: inductive, quotient
    case decl =>
      decl.ty.constants.foreach(checkAxioms)
  })

  def handleArg(name: Name): Unit = {
    checkAxioms(name)
    printDecl(name)
  }
}

case class MainOpts(
    inputFile: String = "",

    printAllDecls: Boolean = false,
    printDecls: Seq[Name] = Seq(),
    printDependencies: Boolean = false,

    showImplicits: Boolean = false,
    useNotation: Boolean = true,
    hideProofs: Boolean = true,
    hideProofTerms: Boolean = false
) {
  def prettyOpts = PrettyOptions(
    showImplicits = showImplicits,
    hideProofs = hideProofs, hideProofTerms = hideProofTerms,
    showNotation = useNotation
  )
}
object MainOpts {
  val parser = new scopt.OptionParser[MainOpts]("trepplein") {
    head("trepplein", "1.0")
    override def showUsageOnError = true

    opt[Unit]('a', "print-all-decls").action((_, c) => c.copy(printAllDecls = true))
      .text("print all checked declarations")
    opt[String]('p', "print-decl").unbounded().valueName("decl.name")
      .action((x, c) => c.copy(printDecls = c.printDecls :+ Name.ofString(x)))
      .text("print specified declarations")
    opt[Unit]('d', "print-dependencies")
      .action((_, c) => c.copy(printDependencies = true))
      .text("print dependencies of specified declarations as well")

    opt[Boolean]("show-implicits").action((x, c) => c.copy(showImplicits = x))
      .text("show implicit arguments").valueName("yes/no")
    opt[Boolean]("use-notation").action((x, c) => c.copy(useNotation = x))
      .text("use notation for infix/prefix/postfix operators").valueName("yes/no")
    opt[Boolean]("hide-proofs").action((x, c) => c.copy(hideProofs = x))
      .text("hide proofs of lemmas").valueName("yes/no")
    opt[Boolean]("hide-proof-terms").action((x, c) => c.copy(hideProofTerms = x))
      .text("hide all proof terms").valueName("yes/no")

    help("help").text("prints this usage text")

    arg[String]("<file>").required().action((x, c) =>
      c.copy(inputFile = x)).text("exported file to check")
  }
}

object main {
  def main(args: Array[String]): Unit =
    MainOpts.parser.parse(args, MainOpts()) match {
      case Some(opts) =>
        val exportedCommands = TextExportParser.parseFile(opts.inputFile)

        val preEnv = exportedCommands.collect { case ExportedModification(mod) => mod }
          .foldLeft[PreEnvironment](Environment.default)(_.add(_))

        val notations = Map() ++ exportedCommands.
          collect { case ExportedNotation(not) => not.fn -> not }.
          reverse // the beautiful unicode notation is exported first

        val printer = new LibraryPrinter(preEnv, notations, print, opts.prettyOpts,
          printDependencies = opts.printDependencies || opts.printAllDecls)
        val declsToPrint = if (opts.printAllDecls) preEnv.declarations.keys else opts.printDecls
        declsToPrint.foreach(printer.handleArg)

        Await.result(preEnv.force, Duration.Inf) match {
          case Left(exs) =>
            for (ex <- exs) println(ex)
            sys.exit(1)
          case Right(env) =>
            println(s"-- successfully checked ${env.declarations.size} declarations")
        }
      case _ => sys.exit(1)
    }
}