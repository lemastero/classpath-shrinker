package io.github.retronym.classpathshrinker

import java.io.File
import java.net.URI

import scala.annotation.tailrec
import scala.reflect.io.AbstractFile
import scala.tools.nsc.classpath.ClassPathFactory
import scala.tools.nsc.plugins.{Plugin, PluginComponent}
import scala.tools.nsc.{Global, Phase}
import scala.tools.util.PathResolver

class ClassPathShrinker(val global: Global) extends Plugin {

  val name = "classpath-shrinker"
  val description = "Warns about classpath entries that are not directly needed."
  val components = List[PluginComponent](Component)

  private object Component extends PluginComponent {
    val global: ClassPathShrinker.this.global.type = ClassPathShrinker.this.global
    import global._

    override val runsAfter = List("jvm")

    val phaseName = ClassPathShrinker.this.name

    override def newPhase(prev: Phase): StdPhase = new StdPhase(prev) {
      override def run(): Unit = {
        super.run()
        val usedJars = findUsedJars
        val usedClasspathStrings = usedJars.toList.map(_.toString).sorted
        val userClasspathURLs = new ClassPathFactory(settings).classesInExpandedPath(settings.classpath.value).flatMap(_.asURLs)
        def toJar(u: URI): Option[File] = util.Try { new File(u) }.toOption.filter(_.getName.endsWith(".jar"))
        val userClasspathStrings = userClasspathURLs.flatMap(x => toJar(x.toURI)).map(_.getPath).toList
        val unneededClasspath = userClasspathStrings.filterNot(s => usedClasspathStrings.contains(s))
        if (unneededClasspath.nonEmpty) {
          warning("classpath-shrinker detected the following unused classpath entries: \n" + unneededClasspath.mkString("\n"))
        }
      }
      override def apply(unit: CompilationUnit): Unit = ()
    }
  }

  import global._

  private def findUsedJars: Set[AbstractFile] = {
    val jars = collection.mutable.Set[AbstractFile]()

    def walkTopLevels(root: Symbol): Unit = {
      def safeInfo(sym: Symbol): Type = if (sym.hasRawInfo && sym.rawInfo.isComplete) sym.info else NoType
      def packageClassOrSelf(sym: Symbol): Symbol = if (sym.hasPackageFlag && !sym.isModuleClass) sym.moduleClass else sym

      val it = safeInfo(packageClassOrSelf(root)).decls.iterator
      while (it.hasNext) {
        val x = it.next()
        if (x == root) ()
        else if (x.hasPackageFlag) walkTopLevels(x)
        else if (x.owner == root) { // exclude package class members
          if (x.hasRawInfo && x.rawInfo.isComplete) {
            val assocFile = x.associatedFile
            if (assocFile.path.endsWith(".class") && assocFile.underlyingSource.isDefined)
              jars += assocFile.underlyingSource.get
          }
        }
      }
    }
    exitingTyper {
      walkTopLevels(RootClass)
    }
    jars.toSet
  }
}
