package org.jetbrains.plugins.scala.lang.libraryInjector

import java.io.{BufferedOutputStream, File, FileOutputStream}
import java.util.zip.{ZipEntry, ZipOutputStream}

import com.intellij.compiler.CompilerTestUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.ModuleTestCase
import org.jetbrains.plugins.scala.PerfCycleTests
import org.jetbrains.plugins.scala.base.libraryLoaders._
import org.jetbrains.plugins.scala.compiler.CompileServerLauncher
import org.jetbrains.plugins.scala.components.libinjection.LibraryInjectorLoader
import org.jetbrains.plugins.scala.debugger._
import org.jetbrains.plugins.scala.lang.libraryInjector.LibraryInjectorTest.InjectorLibraryLoader
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.SyntheticMembersInjector
import org.jetbrains.plugins.scala.util.ScalaUtil
import org.junit.Assert.assertTrue
import org.junit.experimental.categories.Category

/**
  * Created by mucianm on 16.03.16.
  */
@Category(Array(classOf[PerfCycleTests]))
class LibraryInjectorTest extends ModuleTestCase with ScalaSdkOwner {

  override implicit val version: ScalaVersion = Scala_2_11

  override implicit protected def module: Module = getModule

  override protected def librariesLoaders: Seq[LibraryLoader] = Seq(
    ScalaLibraryLoader(isIncludeReflectLibrary = true),
    JdkLoader(getTestProjectJdk),
    SourcesLoader(project.getBasePath),
    InjectorLibraryLoader()
  )

  override protected def getTestProjectJdk: Sdk = DebuggerTestUtil.findJdk8()

  override def setUp(): Unit = {
    super.setUp()

    CompilerTestUtil.enableExternalCompiler()
    DebuggerTestUtil.enableCompileServer(true)
    DebuggerTestUtil.forceJdk8ForBuildProcess()

    setUpLibraries()
  }

  protected override def tearDown() {
    CompilerTestUtil.disableExternalCompiler(getProject)
    CompileServerLauncher.instance.stop()

    tearDownLibraries()
    super.tearDown()
  }

  def testSimple() {
    LibraryLoader.storePointers()

    val injectorLoader = LibraryInjectorLoader.getInstance(getProject)
    val classes = injectorLoader.getInjectorClasses(classOf[SyntheticMembersInjector])

    assertTrue(classes.nonEmpty)
  }
}

object LibraryInjectorTest {

  case class InjectorLibraryLoader(implicit val module: Module) extends ThirdPartyLibraryLoader {
    override protected val name: String = "injector"

    override protected def path(implicit sdkVersion: ScalaVersion): String = {
      val tmpDir = ScalaUtil.createTmpDir("injectorTestLib")
      InjectorLibraryLoader.simpleInjector.zip(tmpDir).getAbsolutePath
    }
  }

  object InjectorLibraryLoader {
    private val simpleInjector: ZDirectory = {
      val manifest =
        """
          |<intellij-compat>
          |    <scala-plugin since-version="0.0.0" until-version="9.9.9">
          |        <psi-injector interface="org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.SyntheticMembersInjector"
          |         implementation="com.foo.bar.Implementation">
          |            <source>META-INF/Implementation.scala</source>
          |            <source>META-INF/Foo.scala</source>
          |        </psi-injector>
          |    </scala-plugin>
          |</intellij-compat>
          |
      """.stripMargin

      val implementationClass =
        """
          |package com.foo.bar
          |import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.SyntheticMembersInjector
          |
          |class Implementation extends SyntheticMembersInjector { val foo = new Foo }
        """.stripMargin

      val fooClass =
        """
          |package com.foo.bar
          |class Foo
        """.stripMargin

      ZDirectory("META-INF",
        Seq(
          ZFile(LibraryInjectorLoader.INJECTOR_MANIFEST_NAME, manifest),
          ZFile("Implementation.scala", implementationClass),
          ZFile("Foo.scala", fooClass)
        )
      )
    }
  }

  sealed trait Zippable {
    val entryName: String

    def withParent(name: String): Zippable

    def zipTo(implicit outputStream: ZipOutputStream): Unit = {
      outputStream.putNextEntry(new ZipEntry(entryName))
      withStream
      outputStream.closeEntry()
    }

    protected def withStream(implicit outputStream: ZipOutputStream): Unit
  }

  case class ZFile(name: String, data: String) extends Zippable {
    override val entryName: String = name

    override def withParent(parentName: String): ZFile = copy(name = s"$parentName/$name")

    override protected def withStream(implicit outputStream: ZipOutputStream): Unit =
      outputStream.write(data.getBytes("UTF-8"), 0, data.length)
  }

  case class ZDirectory(name: String, files: Seq[Zippable]) extends Zippable {
    override val entryName: String = s"$name/"

    def zip(toDir: File): File = {
      val result = new File(toDir, "dummy_lib.jar")
      writeTo(result)
      result
    }

    private def writeTo(file: File): Unit = {
      val outputStream: ZipOutputStream = null
      try {
        val outputStream = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)))
        withStream(outputStream)
      } finally {
        if (outputStream != null) {
          outputStream.close()
        }
      }
    }

    override def withParent(parentName: String): ZDirectory = copy(name = s"$parentName/$name")

    override protected def withStream(implicit outputStream: ZipOutputStream): Unit =
      files.map(_.withParent(name)).foreach(_.zipTo)
  }

}