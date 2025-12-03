import mill._
import mill.scalalib._
import mill.scalalib.publish._
import coursier.maven.MavenRepository
import $file.`dependencies`.hardfloat.common
import $file.`dependencies`.cde.common
import $file.`dependencies`.diplomacy.common
import $file.`dependencies`.chisel.build
import $file.common

object v {
  val scala = "2.13.12"
  // the first version in this Map is the mainly supported version which will be used to run tests
  val chiselCrossVersions = Map(
    "6.7.0" -> (ivy"org.chipsalliance::chisel:6.7.0", ivy"org.chipsalliance:::chisel-plugin:6.7.0"),
    // build from project from source
    "source" -> (ivy"org.chipsalliance::chisel:99", ivy"org.chipsalliance:::chisel-plugin:99"),
  )
  val mainargs = ivy"com.lihaoyi::mainargs:0.5.0"
  val json4sJackson = ivy"org.json4s::json4s-jackson:4.0.5"
  val scalaReflect = ivy"org.scala-lang:scala-reflect:${scala}"
  val sourcecode = ivy"com.lihaoyi::sourcecode:0.3.1"
  val sonatypesSnapshots = Seq(
    MavenRepository("https://s01.oss.sonatype.org/content/repositories/snapshots")
  )
}

// Build form source only for dev
object chisel extends Chisel

trait Chisel
  extends millbuild.dependencies.chisel.build.Chisel {
  def crossValue = v.scala
  override def millSourcePath = os.pwd / "dependencies" / "chisel"
  def scalaVersion = T(v.scala)
}

object macros extends Macros

trait Macros
  extends millbuild.common.MacrosModule
    with RocketChipPublishModule
    with SbtModule {

  def scalaVersion: T[String] = T(v.scala)

  def scalaReflectIvy = v.scalaReflect
}

object hardfloat extends mill.define.Cross[Hardfloat](v.chiselCrossVersions.keys.toSeq)

trait Hardfloat
  extends millbuild.dependencies.hardfloat.common.HardfloatModule
    with RocketChipPublishModule
    with Cross.Module[String] {

  def scalaVersion: T[String] = T(v.scala)

  override def millSourcePath = os.pwd / "dependencies" / "hardfloat" / "hardfloat"

  def chiselModule = Option.when(crossValue == "source")(chisel)

  def chiselPluginJar = T(Option.when(crossValue == "source")(chisel.pluginModule.jar()))

  def chiselIvy = Option.when(crossValue != "source")(v.chiselCrossVersions(crossValue)._1)

  def chiselPluginIvy = Option.when(crossValue != "source")(v.chiselCrossVersions(crossValue)._2)

  def repositoriesTask = T.task(super.repositoriesTask() ++ v.sonatypesSnapshots)
}

object cde extends CDE

trait CDE
  extends millbuild.dependencies.cde.common.CDEModule
    with RocketChipPublishModule
    with ScalaModule {

  def scalaVersion: T[String] = T(v.scala)

  override def millSourcePath = os.pwd / "dependencies" / "cde" / "cde"
}

object diplomacy extends mill.define.Cross[Diplomacy](v.chiselCrossVersions.keys.toSeq)

trait Diplomacy
    extends millbuild.dependencies.diplomacy.common.DiplomacyModule
    with RocketChipPublishModule
    with Cross.Module[String] {

  override def scalaVersion: T[String] = T(v.scala)

  override def millSourcePath = os.pwd / "dependencies" / "diplomacy" / "diplomacy"

  // dont use chisel from source
  def chiselModule = Option.when(crossValue == "source")(chisel)
  def chiselPluginJar = T(Option.when(crossValue == "source")(chisel.pluginModule.jar()))

  // use chisel from ivy
  def chiselIvy = Option.when(crossValue != "source")(v.chiselCrossVersions(crossValue)._1)
  def chiselPluginIvy = Option.when(crossValue != "source")(v.chiselCrossVersions(crossValue)._2)

  // use CDE from source until published to sonatype
  def cdeModule = cde

  def sourcecodeIvy = v.sourcecode
}

object rocketchip extends Cross[RocketChip](v.chiselCrossVersions.keys.toSeq)

trait RocketChip
  extends millbuild.common.RocketChipModule
    with RocketChipPublishModule
    with SbtModule
    with Cross.Module[String] {
  def scalaVersion: T[String] = T(v.scala)

  override def millSourcePath = super.millSourcePath / os.up

  def chiselModule = Option.when(crossValue == "source")(chisel)

  def chiselPluginJar = T(Option.when(crossValue == "source")(chisel.pluginModule.jar()))

  def chiselIvy = Option.when(crossValue != "source")(v.chiselCrossVersions(crossValue)._1)

  def chiselPluginIvy = Option.when(crossValue != "source")(v.chiselCrossVersions(crossValue)._2)

  def macrosModule = macros

  def hardfloatModule = hardfloat(crossValue)

  def cdeModule = cde

  def diplomacyModule = diplomacy(crossValue)

  def diplomacyIvy = None

  def mainargsIvy = v.mainargs

  def json4sJacksonIvy = v.json4sJackson

  def repositoriesTask = T.task(super.repositoriesTask() ++ v.sonatypesSnapshots)
}

trait RocketChipPublishModule
  extends PublishModule {
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "org.chipsalliance",
    url = "http://github.com/chipsalliance/rocket-chip",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("chipsalliance", "rocket-chip"),
    developers = Seq(
      Developer("aswaterman", "Andrew Waterman", "https://aspire.eecs.berkeley.edu/author/waterman/")
    )
  )

  override def publishVersion: T[String] = T("1.6-SNAPSHOT")
}

// Tests
trait Emulator extends Cross.Module2[String, String] {
  val top: String = crossValue
  val config: String = crossValue2

  object generator extends Module {
    def elaborate = T {
      os.proc(
        mill.util.Jvm.javaExe,
        "-jar",
        rocketchip(v.chiselCrossVersions.keys.head).assembly().path,
        "--dir", T.dest.toString,
        "--top", top,
        config.split('_').flatMap(c => Seq("--config", c)),
      ).call()
      PathRef(T.dest)
    }

    def chiselAnno = T {
      os.walk(elaborate().path).collectFirst { case p if p.last.endsWith("anno.json") => p }.map(PathRef(_)).get
    }

    def chirrtl = T {
      os.walk(elaborate().path).collectFirst { case p if p.last.endsWith("fir") => p }.map(PathRef(_)).get
    }
  }

  object litexgenerate extends Module {
    def compile = T {
      os.proc("firtool",
        generator.chirrtl().path,
        s"--annotation-file=${generator.chiselAnno().path}",
        "--disable-annotation-unknown",
        "-dedup",
        "-O=debug",
        "--split-verilog",
        "--preserve-values=named",
        "--output-annotation-file=mfc.anno.json",
        "--lowering-options=disallowLocalVariables",
        s"-o=${T.dest}"
      ).call(T.dest)
      PathRef(T.dest)
    }

    def rtls = T {
      os.read(compile().path / "filelist.f").split("\n").map(str =>
        try {
          os.Path(str)
        } catch {
          case e: IllegalArgumentException if e.getMessage.contains("is not an absolute path") =>
            compile().path / str.stripPrefix("./")
        }
      ).filter(p => p.ext == "v" || p.ext == "sv").map(PathRef(_)).toSeq
    }
  }

  object mfccompiler extends Module {
    def compile = T {
      os.proc("firtool",
        generator.chirrtl().path,
        s"--annotation-file=${generator.chiselAnno().path}",
        "--disable-annotation-unknown",
        "-dedup",
        "-O=debug",
        "--split-verilog",
        "--preserve-values=named",
        "--output-annotation-file=mfc.anno.json",
        s"-o=${T.dest}"
      ).call(T.dest)
      PathRef(T.dest)
    }

    def rtls = T {
      os.read(compile().path / "filelist.f").split("\n").map(str =>
        try {
          os.Path(str)
        } catch {
          case e: IllegalArgumentException if e.getMessage.contains("is not an absolute path") =>
            compile().path / str.stripPrefix("./")
        }
      ).filter(p => p.ext == "v" || p.ext == "sv").map(PathRef(_)).toSeq
    }
  }



}

/** object to elaborate verilated emulators. */
object emulator extends Cross[Emulator](
  // RocketSuiteA
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DefaultConfig"),
  // RocketSuiteB
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DefaultBufferlessConfig"),
  // RocketSuiteC
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.TinyConfig"),

  // Unittest
  ("freechips.rocketchip.unittest.TestHarness", "freechips.rocketchip.unittest.AMBAUnitTestConfig"),
  ("freechips.rocketchip.unittest.TestHarness", "freechips.rocketchip.unittest.TLSimpleUnitTestConfig"),
  ("freechips.rocketchip.unittest.TestHarness", "freechips.rocketchip.unittest.TLWidthUnitTestConfig"),
  // DTM
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.WithJtagDTMSystem_freechips.rocketchip.system.WithDebugSBASystem_freechips.rocketchip.system.DefaultConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.WithJtagDTMSystem_freechips.rocketchip.system.WithDebugSBASystem_freechips.rocketchip.system.DefaultRV32Config"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.WithJtagDTMSystem_freechips.rocketchip.system.DefaultConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.WithJtagDTMSystem_freechips.rocketchip.system.DefaultRV32Config"),
  // Miscellaneous
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DefaultSmallConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DualBankConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DualChannelConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DualChannelDualBankConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.RoccExampleConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.Edge128BitConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.Edge32BitConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.QuadChannelBenchmarkConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.EightChannelConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DualCoreConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.MemPortOnlyConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.MMIOPortOnlyConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.CloneTileConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.HypervisorConfig"),
  //
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DefaultRV32Config"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DefaultFP16Config"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DefaultBConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DefaultRV32BConfig"),

)






