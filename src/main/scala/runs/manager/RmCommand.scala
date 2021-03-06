package runs.manager

import java.nio.file.Paths

import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.{Blocker, ExitCode, IO}
import cats.implicits._
import doobie.h2.H2Transactor
import doobie.implicits._
import runs.manager.Main._

import scala.language.postfixOps

trait RmCommand {
  def rmCommand(pattern: Option[String], active: Boolean)(
    implicit blocker: Blocker,
    xa: H2Transactor[IO],
    yes: Boolean
  ): IO[ExitCode] = {
    if (!active && pattern.isEmpty)
      putStrLn("This will delete all runs. Are you sure?") >> readLn
    else IO.unit
    for {
      conditions <- selectConditions(pattern, active)
      results <- nameContainerQuery(conditions).transact(xa)
      _ <- {
        val names = results.map(_.name)
        val containerIds = results.map(_.containerId)
        putStrLn(if (yes) {
          "Removing the following runs:"
        } else {
          "Remove the following runs?"
        }) >>
          names.traverse(name => putStrLn(Console.RED + name + Console.RESET)) >> pause >>
          killContainers(containerIds) >>
          rmStatement(names).transact(xa)
      }
    } yield ExitCode.Success
  }
}
