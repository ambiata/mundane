package com.ambiata.mundane
package io

import com.ambiata.mundane.testing.RIOMatcher._
import java.io._
import org.specs2._
import scala.io.Codec
import scalaz._, Scalaz._

class StreamsSpec extends Specification with ScalaCheck { def is = s2"""

Streams should be able to:
  read string from stream                       $read
  write string to stream                        $write
  read & write strings symmetrically            $roundtrip
  read bytes from stream                        $bytes

"""

  def read = prop((s: String) => {
    val in = new ByteArrayInputStream(s.getBytes(Codec.UTF8.name))
    Streams.read(in) must beOkValue(s)
  })

  def write = prop((s: String) => {
    val out = new ByteArrayOutputStream()
    Streams.write(out, s).map(_ => out.toByteArray) must beOkValue(s.getBytes(Codec.UTF8.name))
  })

  def roundtrip = prop((s: String) => {
    val out = new ByteArrayOutputStream()
    def in = new ByteArrayInputStream(out.toByteArray)
    val action = Streams.write(out, s) >> Streams.read(in)
    action must beOkValue(s)
  })

  def bytes =  prop((bs: Array[Byte]) => {
    val in = new ByteArrayInputStream(bs)
    Streams.readBytes(in) must beOkValue(bs)
  })
}
