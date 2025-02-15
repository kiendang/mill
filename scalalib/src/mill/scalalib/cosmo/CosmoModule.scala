package mill.scalalib.cosmo

import mill.{T, Task}
import mill.api.{PathRef, Result}
import mill.scalalib._

import scala.jdk.CollectionConverters._

/**
 * Provides a [[CosmoModule.cosmoAssembly task]] to build a cross-platfrom executable assembly jar
 * by prepending an [[https://justine.lol/ape.html Actually Portable Executable (APE)]]
 * launcher binary compiled with the [[https://justine.lol/cosmopolitan/index.html Cosmopolitan Libc]]
 */
trait CosmoModule extends mill.Module with AssemblyModule {
  def finalMainClass: T[String]

  def forkArgs(argv0: String): Task[Seq[String]] = Task.Anon { Seq[String]() }

  def cosmoccVersion: T[String] = Task { "" }

  def cosmocc: T[PathRef] = Task(persistent = true) {
    val version = if (cosmoccVersion().isEmpty) "" else s"-${cosmoccVersion()}"
    val versionedCosmocc = s"cosmocc${version}"
    val zip = Task.dest / s"${versionedCosmocc}.zip"
    val dest = Task.dest / versionedCosmocc / "bin" / "cosmocc"

    if (!os.exists(dest)) {
      os.write.over(
        zip,
        requests.get.stream(s"https://cosmo.zip/pub/cosmocc/${versionedCosmocc}.zip")
      )
      os.remove.all(Task.dest / versionedCosmocc)
      os.call(("unzip", zip, "-d", Task.dest / versionedCosmocc))
    }

    PathRef(dest)
  }

  def cosmoLauncherScript: T[String] = Task {
    val start = 1
    val size0 = start + forkArgs().length
    val addForkArgs = forkArgs()
      .zip(start until size0)
      .map { (arg, i) =>
        s"""all_argv[${i}] = (char*)malloc((strlen("${arg}") + 1) * sizeof(char));
           |strcpy(all_argv[${i}], "${arg}");
        """.stripMargin
      }.mkString("\n")

    val args = forkArgs("%s")()
    val size = size0 + args.length
    val addForkArgsArgv0 = args
      .zip(size0 until size)
      .map { (arg, i) =>
        s"""char* s${i};
           |asprintf(&s${i}, "${arg}", argv[0]);
           |all_argv[${i}] = (char*)malloc((strlen(s${i}) + 1) * sizeof(char));
           |strcpy(all_argv[${i}], s${i});
        """.stripMargin
      }.mkString("\n");

    val preArgvSize = size + 3;

    s"""#include <stdlib.h>
       |#include <stdio.h>
       |#include <errno.h>
       |
       |int main(int argc, char* argv[]) {
       |  size_t preargv_size = ${preArgvSize};
       |  size_t total = preargv_size + argc;
       |  char *all_argv[total];
       |  memset(all_argv, 0, sizeof(all_argv));
       |
       |  all_argv[0] = (char*)malloc((strlen(argv[0]) + 1) * sizeof(char));
       |  strcpy(all_argv[0], argv[0]);
       |  ${addForkArgs}
       |  ${addForkArgsArgv0}
       |  all_argv[${size}] = (char*)malloc((strlen("-cp") + 1) * sizeof(char));
       |  strcpy(all_argv[${size}], "-cp");
       |  all_argv[${size + 1}] = (char*)malloc((strlen(argv[0]) + 1) * sizeof(char));
       |  strcpy(all_argv[${size + 1}], argv[0]);
       |  all_argv[${size + 2}] = (char*)malloc((strlen("${finalMainClass()}") + 1) * sizeof(char));
       |  strcpy(all_argv[${size + 2}], "${finalMainClass()}");
       |
       |  int i = preargv_size;
       |  for (int count = 1; count < argc; count++) {
       |    all_argv[i] = (char*)malloc((strlen(argv[count]) + 1) * sizeof(char));
       |    strcpy(all_argv[i], argv[count]);
       |    i++;
       |  }
       |
       |  all_argv[total - 1] = NULL;
       |
       |  execvp("java", all_argv);
       |  if (errno == ENOENT) {
       |    execvp("java.exe", all_argv);
       |  }
       |
       |  perror("java");
       |  exit(EXIT_FAILURE);
       |}
    """.stripMargin
  }

  def cosmoWindowsExtras: T[Option[PathRef]] = Task(persistent = true) {
    if (scala.util.Properties.isWin) {
      val tools = Seq(
        "dash" -> "dash.com",
        "rm.ape" -> "rm",
        "mv.ape" -> "mv"
      )

      tools.foreach { (from, to) =>
        if (!os.exists(Task.dest / to)) {
          os.write(
            Task.dest / to,
            requests.get.stream(s"https://cosmo.zip/pub/cosmos/bin/${from}")
          )
        }
      }

      Some(PathRef(Task.dest))
    } else None
  }

  def cosmoCompiledLauncherScript: T[PathRef] = Task {
    os.write(Task.dest / "launcher.c", cosmoLauncherScript())

    val cmd = (
      cosmocc().path,
      "-mtiny",
      "-O3",
      "-o",
      Task.dest / "launcher",
      Task.dest / "launcher.c"
    )

    cosmoWindowsExtras() match {
      case Some(d) =>
        val env = System.getenv().asScala.toMap
        val pathEnvVar = env.find((k, _) => k.toUpperCase == "PATH")
        val updatedEnv = pathEnvVar.fold(env.updated("Path", d.path.toString)) {
          (k, v) => env.updated(k, s"${d.path.toString}:${v}")
        }

        os.call((d.path / "dash.com", cmd), env = updatedEnv, propagateEnv = false)
      case None => os.call(cmd)
    }

    PathRef(Task.dest / "launcher")
  }

  def cosmoAssembly: T[PathRef] = Task {
    val prepend = os.read.bytes(cosmoCompiledLauncherScript().path)
    val upstream = upstreamAssembly()

    val created = Assembly.create0(
      destJar = Task.dest / "out.jar.exe",
      inputPaths = Seq.from(localClasspath().map(_.path)),
      manifest = manifest(),
      prepend = Some(prepend),
      base = Some(upstream),
      assemblyRules = assemblyRules
    )
    // See https://github.com/com-lihaoyi/mill/pull/2655#issuecomment-1672468284
    val problematicEntryCount = 65535

    if (created.entries > problematicEntryCount) {
      Result.Failure(
        s"""The created assembly jar contains more than ${problematicEntryCount} ZIP entries.
           |Prepended JARs of that size are known to not work correctly.
         """.stripMargin
      )
    } else {
      Result.Success(created.pathRef)
    }
  }
}
