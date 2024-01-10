package baseband

import chisel3._
import chiseltest._
import chiseltest.{VerilatorBackendAnnotation, TreadleBackendAnnotation, WriteVcdAnnotation}
import org.scalatest.flatspec.AnyFlatSpec

class LoopbackTest extends AnyFlatSpec with ChiselScalatestTester {
  it should "Fuzz a simple loopback" in {
    for (i <- 1 to 32) {
      test(new Loopback(UInt(i.W))).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
        for (_ <- 0 until 10) {
          val x = scala.util.Random.nextInt(scala.math.pow(2, i).toInt)
          val y = scala.util.Random.nextInt(scala.math.pow(2, i).toInt)
          val s = scala.util.Random.nextBoolean()

          c.io.left.in.poke(x.U)
          c.io.right.in.poke(y.U)
          c.io.select.poke(s.asBool())
          assert(c.io.right.out.peek().litValue() == x)
          assert(c.io.left.out.peek().litValue() == (if (s) x else y))
        }
      }
    }
  }

  it should "Fuzz a decoupled loopback" in {
    for (i <- 1  to 32) {
      test(new DecoupledLoopback(UInt(i.W))).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
        for (_ <- 0 until 10) {
          val x = scala.util.Random.nextInt(scala.math.pow(2, i).toInt)
          val y = scala.util.Random.nextInt(scala.math.pow(2, i).toInt)
          val s = scala.util.Random.nextBoolean()

          c.io.left.in.valid.poke(true.B)
          c.io.right.in.valid.poke(true.B)
          c.io.left.out.ready.poke(true.B)
          c.io.right.out.ready.poke(true.B)
          c.io.left.in.bits.poke(x.U)
          c.io.right.in.bits.poke(y.U)
          c.io.select.poke(s.asBool())

          if (s) {
            assert(!c.io.right.out.valid.peek().litToBoolean)
            assert(!c.io.right.in.ready.peek().litToBoolean )
            assert(c.io.left.out.bits.peek().litValue() == x)
          } else {
            assert(c.io.right.out.valid.peek().litToBoolean)
            assert(c.io.right.in.ready.peek().litToBoolean)
            assert(c.io.right.out.bits.peek().litValue() == x)
            assert(c.io.left.out.bits.peek().litValue() == y)
          }

          c.clock.step()
        }
      }
    }
  }
}
