package runs.manager

import java.nio.file.Path
import java.util.Date

import cats.Monad
import cats.effect.Console.io.putStrLn
import cats.effect.ExitCase.Completed
import cats.effect.{Blocker, Clock, ExitCode, IO}
import cats.implicits._
import doobie._
import Fragments.in
import cats.data.NonEmptyList
import doobie.h2.H2Transactor
import doobie.implicits._
import fs2.io.file._
import fs2.text
import io.github.vigoo.prox.Process.{ProcessImpl, ProcessImplO}
import io.github.vigoo.prox.{Process, ProcessResult}
import runs.manager.Main.{existingVolumes, killProc, putStrLnBold, _}

import scala.concurrent.duration.SECONDS

case class PathMove(former: Path, current: Path)
case class ConfigTuple(name: String,
                       configScript: Option[String],
                       config: Option[String])
case class DockerPair(containerId: String, volume: String)

case class Existing(name: String, containerId: String, volume: String)

trait NewCommand {
  implicit class ProcessWithCommandString[A, B](p: Process[IO, A, B]) {
    def toList: List[String] = p.command :: p.arguments
    def prettyString: String = {
      p.toList.map(s => s"'$s'").mkString(" ")
    }
    def checkThenPerform(requireYes: Boolean = false)(
      implicit blocker: Blocker,
      yes: Boolean
    ): IO[Option[ProcessResult[A, B]]] = {
      for {
        response <- if (yes)
          IO.pure(true)
        else
          putStrLnBold("Perform the following command?") >> putStrLnRed(
            p.prettyString
          ) >> check(requireYes)
        output <- if (response)
          for { result <- p.run(blocker) } yield Some(result)
        else IO.pure(None)
      } yield output
    }
  }

  def realTime: IO[Date] = {
    val clock: Clock[IO] = Clock.create
    clock.realTime(SECONDS).map(new Date(_))
  }

  def getCommit(implicit blocker: Blocker): IO[String] = {
    val proc: ProcessImpl[IO] =
      Process[IO]("git", List("rev-parse", "HEAD"))
    (proc ># captureOutput).run(blocker).map(_.output).flatMap(IO.pure)
  }

  def checkDirty(implicit blocker: Blocker): IO[Boolean] = {
    val proc: ProcessImpl[IO] =
      Process[IO]("git", List("status", "-s"))
    for {
      result <- (proc ># captureOutput).run(blocker)
    } yield result.output != ""
  }

  def runThenInsert(
    partialRows: List[PartialRunRow],
    dockerRunBase: List[String],
    containerVolume: String,
    follow: Boolean
  )(implicit blocker: Blocker, xa: H2Transactor[IO]): IO[List[RunRow]] = {
    val dockerRun = partialRows.traverse(r => {
      runDocker(
        hostVolume = r.volume,
        dockerRun = dockerRunProc(
          dockerRunBase = dockerRunBase,
          name = r.name,
          hostVolume = r.volume,
          containerVolume = containerVolume,
          image = r.imageId,
          config = r.config
        )
      )
    })
    runBracket(
      partialRows = partialRows,
      dockerRun = dockerRun,
      follow = follow
    )
  }

  def runBracket(
    partialRows: List[PartialRunRow],
    dockerRun: IO[List[DockerPair]],
    follow: Boolean
  )(implicit blocker: Blocker, xa: H2Transactor[IO]): IO[List[RunRow]] = {
    dockerRun.bracketCase { pairs =>
      val newRows: List[RunRow] =
        pairs.map(_.containerId).zip(partialRows).map {
          case (containerId, newRow) => newRow.toRunRow(containerId)
        }
      runInsert(newRows) >> IO.pure(newRows)
    } {
      case (newRuns, Completed) =>
        val printFollow = putStrLnBold("To follow, run:") >>
          newRuns
            .traverse((p: DockerPair) => {
              putStrLnGreen(followProc(p.containerId).prettyString)
            })
            .void
        putStrLnBold("Runs successfully inserted into database.") >>
          printFollow.unlessA(follow)
      case (newRuns, _) =>
        putStrLnBold("Inserting runs failed")
        killProc(newRuns.map(_.containerId)).run(blocker) >>
          rmVolumeProc(newRuns.map(_.volume)).run(blocker).void
    }
  }

  def findExistingRuns(names: NonEmptyList[String])(
    implicit blocker: Blocker,
    xa: H2Transactor[IO],
    yes: Boolean
  ): IO[List[Existing]] = {
//    val drop = sql"DROP TABLE IF EXISTS runs".update.run
    val create = RunRow.createTable.update.run
    val fragment =
      fr"SELECT name, containerId, volume FROM runs WHERE" ++ in(
        fr"name",
        names
      )
    val checkExisting =
      fragment
        .query[Existing]
        .to[List]
    (create, checkExisting).mapN((_, e) => e).transact(xa)
  }

  def checkOverwrite(
    existing: List[String]
  )(implicit blocker: Blocker, xa: H2Transactor[IO], yes: Boolean): IO[Unit] = {
    (putStrLnBold(
      if (yes) "Overwriting the following rows:"
      else "Overwrite the following rows?"
    ) >>
      existing.traverse(putStrLnRed) >>
      check()).unlessA(existing.isEmpty)
  }

  def findRunsSharingVolumes(volumes: NonEmptyList[String])(
    implicit blocker: Blocker,
    xa: H2Transactor[IO],
    yes: Boolean
  ): IO[List[(String, String)]] = {
    val fragment =
      fr"SELECT name, volume FROM runs WHERE" ++ in(fr"volume", volumes)
    fragment
      .query[(String, String)]
      .to[List]
      .transact(xa)
  }

  def showInUseVolumes(
    existing: List[(String, String)]
  )(implicit blocker: Blocker, xa: H2Transactor[IO], yes: Boolean): IO[Unit] = {
    if (existing.isEmpty) IO.unit
    else {
      val volumes = existing.map(_._2).toSet
      val plural = volumes.size > 1
      putStrLnBold(
        if (plural)
          "The following docker volumes are in use by existing runs:"
        else
          s"The docker volume ${volumes.toList.mkString("")} is in use by existing runs:"
      ) >>
        existing.traverse {
          case (name, volume) =>
            putStrLnRed(if (plural) s"$name: $volume" else name)
        }
    }.void
  }

  def getCommitMessage(implicit blocker: Blocker): IO[String] = {
    val proc: ProcessImpl[IO] =
      Process[IO]("git", List("log", "-1", "--pretty=%B"))
    (proc ># captureOutput).run(blocker).map(_.output).flatMap(IO.pure)
  }

  def getDescription(
    description: Option[String]
  )(implicit blocker: Blocker): IO[String] = {
    description match {
      case Some(d) => IO.pure(d)
      case None    => getCommitMessage(blocker)
    }
  }

  def buildImage(image: String, imageBuildPath: Path, dockerfilePath: Path)(
    implicit blocker: Blocker
  ): IO[String] = {
    val buildProc = Process[IO](
      "docker",
      List(
        "build",
        "-q",
        "-f",
        dockerfilePath.toString,
        "-t",
        image,
        imageBuildPath.toString
      )
    ) ># captureOutput
    val pattern = "sha256:(.*)\n".r
    buildProc
      .run(blocker)
      .flatMap(
        result =>
          (result.exitCode match {
            case ExitCode.Success => IO.unit
            case code =>
              IO.raiseError(
                new RuntimeException(s"Process exited with code $code")
              )
          }).flatMap(
            _ =>
              (result.output match {
                case pattern(id) => IO.pure(id)
                case x: String =>
                  IO.raiseError(
                    new RuntimeException(
                      s"docker image '${result.output}' did not match pattern '$pattern': $x"
                    )
                  )
              }).map(id => id)
        )
      )
  }

  def dockerRunProc(dockerRunBase: List[String],
                    name: String,
                    hostVolume: String,
                    containerVolume: String,
                    image: String,
                    config: Option[String]): ProcessImplO[IO, String] = {

    val dockerRun = dockerRunBase ++ List(
      "--name",
      name,
      "--volume",
      s"$hostVolume:$containerVolume",
      image
    ) ++ config.fold(List[String]())(_.replaceAll("""\s+""", " ").split(" ").toList)
    Process[IO](dockerRun.head, dockerRun.tail) ># captureOutput
  }

  def runDocker(dockerRun: ProcessImplO[IO, String], hostVolume: String)(
    implicit blocker: Blocker
  ): IO[DockerPair] = {
    for {
      result <- putStrLn(dockerRun.prettyString) >>
        putStrLnBold("To debug, run:") >>
        putStrLn(
          Console.GREEN +
            dockerRun.toList
              .filterNot(_.matches("-d|--detach".r.regex))
              .mkString(" ") +
            Console.RESET
        ) >>
        dockerRun.run(blocker)
      pair <- result.exitCode match {
        case ExitCode.Success =>
          val containerId = result.output.stripLineEnd
          IO.pure(DockerPair(containerId = containerId, volume = hostVolume))
        case code =>
          IO.raiseError(new RuntimeException(s"Process exited with code $code"))
      }
    } yield pair
  }

  def followDocker(follow: Boolean,
                   rows: List[RunRow])(implicit blocker: Blocker): IO[Unit] = {
    (follow, rows) match {
      case (true, hd :: _) => followProc(hd.containerId).run(blocker).void
      case _               => IO.unit
    }
  }

  def readPath(path: Path)(implicit blocker: Blocker): IO[String] = {
    readAll[IO](path, blocker, 4096)
      .through(text.utf8Decode)
      .compile
      .foldMonoid
  }

  def sampleConfig(configScript: String,
                   interpreter: String,
                   interpreterArgs: List[String],
  )(implicit blocker: Blocker): IO[String] = {
    val runScript: ProcessImplO[IO, String] = Process[IO](
      interpreter,
      interpreterArgs ++ List(configScript)
    ) ># captureOutput
    runScript
      .run(blocker)
      .map(_.output)
  }

  def performChecks(names: List[String],
                    hostVolume: Option[String],
                    killLabel: Option[String],
  )(implicit blocker: Blocker, xa: H2Transactor[IO], yes: Boolean): IO[Unit] = {
    for {
      dirty <- checkDirty
      _ <- (putStrLnBold("Repo is dirty. Are you sure you wish to continue?") >> pause)
        .whenA(dirty)
      containers <- activeContainers(killLabel)
      _ <- killContainers(containers)
      names <- names match {
        case h :: t => IO.pure(new NonEmptyList[String](h, t))
        case Nil =>
          IO.raiseError(new RuntimeException("empty ConfigTuples"))
      }
      existing <- findExistingRuns(names)
      _ <- checkOverwrite(existing map (_.name))
      containers <- activeContainers(killLabel)
      _ <- {
        val containersToKill = existing
          .map(_.containerId)
          .filter((existing: String) => containers.exists(existing.startsWith))
        val dockerKill = killProc(containersToKill)
        dockerKill.checkThenPerform().unlessA(containersToKill.isEmpty)
      }
      volumes = hostVolume.fold(names)(NonEmptyList(_, List()))
      sharingRuns <- findRunsSharingVolumes(volumes)
      _ <- showInUseVolumes(sharingRuns)
      volumesToRemove <- existingVolumes(volumes.toList)
      _ <- rmVolumeProc(volumesToRemove)
        .checkThenPerform(requireYes = sharingRuns.length > 1)
        .unlessA(volumesToRemove.isEmpty)
    } yield ()
  }

  def newCommand(name: String,
                 description: Option[String],
                 image: String,
                 imageBuildPath: Path,
                 dockerfilePath: Path,
                 dockerRunBase: List[String],
                 hostVolume: Option[String],
                 containerVolume: String,
                 killLabel: Option[String],
                 follow: Boolean,
                 newMethod: NewMethod)(implicit blocker: Blocker,
                                       xa: H2Transactor[IO],
                                       yes: Boolean): IO[ExitCode] = {

    implicit val dockerRun: List[String] = dockerRunBase
    val configTuplesOp: IO[List[ConfigTuple]] = newMethod match {
      case Single(config: Option[String]) =>
        IO.pure(List(ConfigTuple(name, None, config)))
      case Multi(
          scriptPath: Path,
          interpreter: String,
          args: List[String],
          numRuns: Int
          ) =>
        for {
          script <- readPath(scriptPath)
          configs <- Monad[IO]
            .replicateA(numRuns, sampleConfig(script, interpreter, args))
        } yield
          configs.zipWithIndex
            .map {
              case (config: String, i) =>
                ConfigTuple(
                  name = s"$name${i.toString}",
                  configScript = Some(script),
                  config = Some(config),
                )
            }
    }
    for {
      tuples <- configTuplesOp
      _ <- performChecks(
        names = tuples.map(_.name),
        hostVolume = hostVolume,
        killLabel = killLabel
      )
      imageId <- buildImage(
        image = image,
        imageBuildPath = imageBuildPath,
        dockerfilePath = dockerfilePath,
      )
      commit <- getCommit
      now <- realTime
      description <- getDescription(description)
      partialRows = tuples.map(t => {
        val config = t.config
          .map(_.replaceAll("<NAME>", t.name).replaceAll("<COMMIT>", commit))
        PartialRunRow(
          commitHash = commit,
          config = config,
          configScript = t.configScript,
          imageId = imageId,
          description = description,
          volume = hostVolume.getOrElse(t.name),
          datetime = now,
          name = t.name,
        )
      })
      rows <- runThenInsert(
        partialRows = partialRows,
        dockerRunBase = dockerRunBase,
        containerVolume = containerVolume,
        follow = follow
      )
      _ <- followDocker(follow, rows)
    } yield ExitCode.Success
  }

}
