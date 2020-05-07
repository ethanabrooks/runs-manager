package lab_notebook

import java.nio.file.{Files, Path, Paths}

import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.ExitCase.Completed
import cats.effect.{Blocker, Concurrent, ExitCode, IO, IOApp, Resource}
import cats.implicits._
import io.circe.parser.decode
import io.github.vigoo.prox.{JVMProcessRunner, Process, ProcessRunner}
import io.github.vigoo.prox.Process.ProcessImpl
import lab_notebook.LabNotebook.DB
import org.rogach.scallop.{
  ScallopConf,
  ScallopOption,
  Subcommand,
  ValueConverter,
  singleArgConverter
}
import slick.jdbc.H2Profile
import slick.jdbc.H2Profile.api._

import scala.language.postfixOps
import scala.util.Try

case class RunRow(
    commit: String,
    config: String,
    containerId: String,
    description: String,
    name: String,
    script: String,
)

class RunTable(tag: Tag) extends Table[RunRow](tag, "Runs") {
  def commit = column[String]("commit")

  def config = column[String]("config")

  def containerId = column[String]("containerId")

  def description = column[String]("description")

  //  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def name = column[String]("name", O.PrimaryKey)

  def script = column[String]("script")

  def * =
    (commit, config, containerId, description, name, script).mapTo[RunRow]
}

class Conf(args: Seq[String]) extends ScallopConf(args) {
  private val pathConverter: ValueConverter[Path] =
    singleArgConverter(Paths.get(_))
  val dbPath: ScallopOption[Path] = opt(required = true)(pathConverter)
  val New = new Subcommand("new") {
    val namePrefix: ScallopOption[String] = opt(required = false)
    val configMap: ScallopOption[Path] = opt(required = true)(pathConverter)
    val runScript: ScallopOption[Path] = opt(required = true)(pathConverter)
    val killScript: ScallopOption[Path] = opt(required = true)(pathConverter)
    val commit: ScallopOption[String] = opt(required = true)
    val description: ScallopOption[String] = opt(required = true)
    for (path <- List(runScript, killScript, configMap))
      validatePathExists(path)
  }
  addSubcommand(New)
  val rm = new Subcommand("rm") {
    val pattern: ScallopOption[String] = opt(required = true)
    val killScript: ScallopOption[Path] = opt(required = true)(pathConverter)
  }
  addSubcommand(rm)
  val lookup = new Subcommand("lookup") {
    val field: ScallopOption[String] =
      opt(
        required = true,
        validate = s => {
          Try {
            classOf[RunRow].getDeclaredField(s)
          }.isSuccess
        }
      )
    val pattern: ScallopOption[String] = opt(required = true)
  }
  addSubcommand(lookup)
  verify()
}

object LabNotebook extends IOApp {
  type DatabaseDef = H2Profile.backend.DatabaseDef
  val table = TableQuery[RunTable]

  implicit class DB(db: DatabaseDef) {
    def execute[X](action: DBIO[X]): IO[X] = {
      IO.fromFuture(IO(db.run(action)))
    }
  }

  object DB {
    def connect(path: Path): Resource[IO, DatabaseDef] =
      Resource.make {
        IO(
          Database.forURL(url = s"jdbc:h2:$path",
                          driver = "org.h2.Driver",
                          keepAliveConnection = true)
        )
      } { db =>
        IO(db.close())
      }
  }

  def newCommand(configMap: Map[String, String],
                 runProc: String => ProcessImpl[IO],
                 killProc: List[String] => ProcessImpl[IO],
                 dbPath: Path,
                 commit: String,
                 namePrefix: String,
                 description: String): IO[ExitCode] = {
    implicit val runner: ProcessRunner[IO] = new JVMProcessRunner

    def insertNewRuns(containerIds: List[String],
                      configMap: Map[String, String]): IO[_] = {
      val newRows = for {
        (id, (name, config)) <- containerIds zip configMap
      } yield {
        RunRow(
          commit = commit,
          config = config,
          containerId = id,
          name = namePrefix + name,
          script = runProc(config).toString,
          description = description,
        )
      }
      val checkExisting =
        table
          .filter(_.name inSet configMap.keys)
          .map(_.name)
          .result
      val upserts = for (row <- newRows) yield table.insertOrUpdate(row)
      DB.connect(dbPath).use { db =>
        db.execute(checkExisting) >>= { existing: Seq[String] =>
          (if (existing.isEmpty) {
             IO.unit
           } else {
             putStrLn("Overwrite the following rows?") >>
               existing.toList.traverse(putStrLn) >>
               readLn
           }) >>
            db.execute(table.schema.createIfNotExists >> DBIO.sequence(upserts))
        }
      }
    }

    def getContainerIds(blocker: Blocker,
                        configMap: Map[String, String]): IO[List[String]] =
      for {
        _ <- putStrLn("Launching run scripts...")
        fibers <- configMap.values.toList.traverse { config =>
          val captureOutput = fs2.text.utf8Decode[IO]
          val proc = runProc(config) ># captureOutput
          // prompt before inserting
          Concurrent[IO].start(proc.run(blocker))
        }
        results <- fibers
          .map(_.join)
          .traverse(_ >>= (r => IO(r.output)))
      } yield results

    Blocker[IO]
      .use { blocker =>
        for {
          result <- getContainerIds(blocker, configMap)
            .bracketCase { containerIds =>
              insertNewRuns(containerIds, configMap)
            } {
              case (_, Completed) =>
                putStrLn("IO operations complete.")
              case (containerIds: List[String], _) =>
                Blocker[IO].use { blocker =>
                  killProc(containerIds).run(blocker)
                }.void
            } as ExitCode.Success
        } yield result
      }

  }

  def lookupCommand(dbPath: Path,
                    field: String,
                    pattern: String): IO[ExitCode] = {
    for {
      ids <- DB.connect(dbPath).use { db =>
        db.execute(
          table
            .filter(_.name like pattern)
            .map((e: RunTable) => {
              field match {
                case "commit"      => e.commit
                case "config"      => e.config
                case "containerId" => e.containerId
                case "description" => e.description
                case "name"        => e.name
                case "script"      => e.script
              }
            })
            .result)
      }
      _ <- if (ids.isEmpty) {
        putStrLn(s"No runs match pattern $pattern")
      } else {
        ids.toList.traverse(putStrLn)
      }
    } yield ExitCode.Success

  }

  def rmCommand(dbPath: Path,
                pattern: String,
                killProc: List[String] => ProcessImpl[IO]): IO[ExitCode] = {
    implicit val runner: ProcessRunner[IO] = new JVMProcessRunner
    DB.connect(dbPath).use { db =>
      val query = table.filter(_.name like pattern)
      db.execute(query.result) >>= { (matches: Seq[RunRow]) =>
        val ids = matches.map(_.containerId).toList
        val runKillProc = Blocker[IO].use(killProc(ids).run(_))
        if (matches.isEmpty) {
          putStrLn(s"No runs match pattern $pattern")
        } else {
          putStrLn("Delete the following rows?") >>
            matches.map(_.name).toList.traverse(putStrLn) >>
            readLn >>
            runKillProc >>
            db.execute(query.delete)
        }
      }
    } >> IO(ExitCode.Success)

  }

  override def run(args: List[String]): IO[ExitCode] = {
    val conf = new Conf(args)
    def killProc(script: Path)(ids: List[String]): ProcessImpl[IO] = {
      Process[IO](script.toString, ids)
    }
    conf.subcommand match {
      case Some(conf.New) =>
        for {
          bytes <- IO(Files.readAllBytes(conf.New.configMap()))
          string = new String(bytes)
          map <- IO.fromEither(decode[Map[String, String]](string))
          runProc = { (config: String) =>
            Process[IO](conf.New.runScript().toString, List(config))
          }
          r <- newCommand(
            configMap = map,
            runProc = runProc,
            killProc = killProc(conf.New.killScript()),
            dbPath = conf.dbPath(),
            commit = conf.New.commit(),
            namePrefix = conf.New.namePrefix.getOrElse(""),
            description = conf.New.description()
          )
        } yield r

      case Some(conf.lookup) =>
        lookupCommand(dbPath = conf.dbPath(),
                      field = conf.lookup.field(),
                      pattern = conf.lookup.pattern())
      case Some(conf.rm) =>
        rmCommand(dbPath = conf.dbPath(),
                  killProc = killProc(conf.rm.killScript()),
                  pattern = conf.rm.pattern())
      case _ => IO(ExitCode.Success)
    }
  }
}
