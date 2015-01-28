package com.ambiata.mundane.io

import com.ambiata.mundane.control._
import com.ambiata.mundane.data._
import com.ambiata.mundane.path._
import java.io._
import java.util.Date
import scala.io.Codec
import scalaz._, Scalaz._, effect._, Effect._
import MemoryConversions._

/**
 * 'LocalPath' is an unknown local location which means that
 * either nothing exists at that location or that possibly
 * something exists and we just don't know yet. A file that
 * is known to exist is denoted by either 'LocalFile' or
 * 'LocalDirectory'.
 */
case class LocalPath(path: Path) {
  def /(other: Path): LocalPath =
    LocalPath(path / other)

  def join(other: Path): LocalPath =
    /(other)

  def |(other: Component): LocalPath =
    LocalPath(path | other)

  def extend(other: Component): LocalPath =
    |(other)

  def /-(other: String): LocalPath =
    LocalPath(path /- other)

  def rebaseTo(other: LocalPath): Option[LocalPath] =
    path.rebaseTo(other.path).map(LocalPath(_))

  def toFile: File =
    path.toFile

  def dirname: LocalPath =
    LocalPath(path.dirname)

  def basename: Option[Component] =
    path.basename

  def touch: RIO[LocalFile] = {
    val file = path.toFile
    for {
      e <- RIO.safe[Boolean](file.exists)
      r <- if (e) RIO.safe[Unit](file.setLastModified(new Date().getTime)).as(LocalFile.unsafe(path.path))
           else write("")
    } yield r
  }

  def append(content: String): RIO[LocalFile] =
    appendWithEncoding(content, Codec.UTF8)

  def appendWithEncoding(content: String, encoding: Codec): RIO[LocalFile] =
    RIO.using(path.toOutputStream)(out => Streams.writeWithEncoding(out, content, encoding)) >>
      LocalFile.unsafe(path.path).pure[RIO]

  def appendLines(content: List[String]): RIO[LocalFile] =
    appendLinesWithEncoding(content, Codec.UTF8)

  def appendLinesWithEncoding(content: List[String], encoding: Codec): RIO[LocalFile] =
    appendWithEncoding(Lists.prepareForFile(content), encoding) >> LocalFile.unsafe(path.path).pure[RIO]

  def appendBytes(content: Array[Byte]): RIO[LocalFile] =
    RIO.using(path.toOutputStream)(Streams.writeBytes(_, content)) >> LocalFile.unsafe(path.path).pure[RIO]


/**
  TODO re-implement write operations to fail if exists first else append
  */
  def write(content: String): RIO[LocalFile] =
    writeWithEncoding(content, Codec.UTF8)

  def writeWithEncoding(content: String, encoding: Codec): RIO[LocalFile] = for {
    _ <- dirname.mkdirs
    _ <- RIO.using(path.toOutputStream) { out =>
      Streams.writeWithEncoding(out, content, encoding) }
  } yield LocalFile.unsafe(path.path)

  def writeLines(content: List[String]): RIO[LocalFile] =
    writeLinesWithEncoding(content, Codec.UTF8)

  def writeLinesWithEncoding(content: List[String], encoding: Codec): RIO[LocalFile] = for {
    _ <- dirname.mkdirs
    _ <- writeWithEncoding(Lists.prepareForFile(content), encoding)
  } yield LocalFile.unsafe(path.path)

  def writeBytes(content: Array[Byte]): RIO[LocalFile] = for {
    _ <- dirname.mkdirs
    _ <- RIO.using(path.toOutputStream)(Streams.writeBytes(_, content))
  } yield LocalFile.unsafe(path.path)

  def writeStream(content: InputStream): RIO[Unit] =  for {
    _ <- dirname.mkdirs
    _ <- RIO.using(path.toOutputStream)(Streams.pipe(content, _))
  } yield LocalFile.unsafe(path.path)

  def overwrite = ???
  def overwriteWithEncoding = ???
  def overwriteLines = ???
  def overwriteLinesWithEncoding = ???
  def overwriteBytes = ???



  def mkdirs: RIO[LocalDirectory] =
    RIO.safe[Boolean](path.toFile.mkdirs).void >>
      RIO.ok(LocalDirectory.unsafe(path.path))

  def exists: RIO[Boolean] =
    determine.map(_.isDefined)

  def unlessExists[A](error: String, thunk: => RIO[A]): RIO[A] =
    exists >>= (e =>
      if (e) RIO.failIO(error)
      else   thunk
    )

  def determine: RIO[Option[LocalFile \/ LocalDirectory]] = {
    val file = path.toFile
    for {
      e <- RIO.safe[Boolean](file.exists)
      o <- if (e)
        RIO.safe[Boolean](file.isFile) >>= ((f: Boolean) => (
          if (f) LocalFile.unsafe(path.path).left
          else   LocalDirectory.unsafe(path.path).right
        ).some.pure[RIO])
      else
        none.pure[RIO]
    } yield o
  }

  def determinef[A](file: LocalFile => RIO[A], directory: LocalDirectory => RIO[A]): RIO[A] =
    determinefWith(file, directory, RIO.failIO("Not a valid File or Directory"))

  def determinefWith[A](file: LocalFile => RIO[A], directory: LocalDirectory => RIO[A], none: RIO[A]): RIO[A] =
    determine >>= ({
      case Some(\/-(v)) =>
        directory(v)
      case Some(-\/(v)) =>
        file(v)
      case None =>
        none
    })

  def determineFile: RIO[LocalFile] =
    determinef(_.pure[RIO], _ => RIO.fail("Not a valid file"))

  def determineDirectory: RIO[LocalDirectory] =
    determinef(_ => RIO.fail("Not a valid directory"), _.pure[RIO])

   /** List all files, will not include directories */
  def listFilesRecursively: RIO[List[LocalFile]] =
    determinef(v => List(v).pure[RIO], d => d.listFilesRecursively)

  def listDirectoriesRecursively: RIO[List[LocalDirectory]] =
    determinef(_ => nil.pure[RIO], d => d.listDirectoriesRecursively)

  /** This will list all Path's including directories  */
  def listPathsRecursively: RIO[List[LocalPath]] =
    determinef(v => List(LocalPath(path)).pure[RIO], d => d.listPathsRecursively)

  /** This will only list directories for a single level */
  def listDirectories: RIO[List[LocalDirectory]] =
    determinef(_ => nil.pure[RIO], d => d.listDirectories)

  /** This will only list files for a single level */
  def listFiles: RIO[List[LocalFile]] =
    determinef(_ => List(LocalPath(path)).traverseU(_.determineFile), d => d.listFiles)

  /** This will only list Path's for a single level (LocalFile and LocalDirectory) */
  def listPaths: RIO[List[LocalPath]] =
    determinef(v => List(LocalPath(path)).pure[RIO], d => d.listPaths)

  def size: RIO[BytesQuantity] =
    listFilesRecursively.map(_.foldMap(_.toFile.length).bytes)

  def delete: RIO[Unit] =
    determinef(_.delete, _.delete)

  def move(destination: LocalPath): RIO[Unit] =
    determinef(file =>
      destination.determinefWith(
          _ => RIO.failIO(s"File exists in target location $destination. Can not move $path file.")
        , d => file.moveTo(d)
        , file.move(destination).void)
      , dir =>
      destination.determinefWith(
          _ => RIO.failIO(s"File eixsts in the target location $destination. Can not move $path directory.")
        , d => dir.moveTo(d)
        , dir.move(destination)
      ))

  def copy(destination: LocalPath): RIO[Unit] =
    determinef(file =>
      destination.determinefWith(
          _ => RIO.failIO(s"File exists in target location $destination. Can not move $path file.")
        , d => file.copyTo(d)
        , file.copy(destination).void)
      , dir =>
      destination.determinefWith(
          _ => RIO.failIO(s"File eixsts in the target location $destination. Can not move $path directory.")
        , d => dir.copyTo(d)
        , dir.copy(destination)
      ))
}

object LocalPath {
  implicit def LocalPathOrder: Order[LocalPath] =
    Order.order((x, y) => x.path.?|?(y.path))

  implicit def LocalPathOrdering =
    LocalPathOrder.toScalaOrdering
}
